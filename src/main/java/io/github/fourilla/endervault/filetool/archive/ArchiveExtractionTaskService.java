package io.github.fourilla.endervault.filetool.archive;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.auth.ClientIpResolver;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.PartialStorageCommitException;
import io.github.fourilla.endervault.storage.StorageBatchCommitResult;
import io.github.fourilla.endervault.storage.StorageBatchEntry;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.task.TaskCanceledException;
import io.github.fourilla.endervault.task.TaskContext;
import io.github.fourilla.endervault.task.TaskManagerService;
import io.github.fourilla.endervault.task.TaskOutcome;
import io.github.fourilla.endervault.task.TaskType;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import io.github.fourilla.endervault.temporary.TemporaryArtifactType;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ArchiveExtractionTaskService {

    private static final Logger logger = LoggerFactory.getLogger(ArchiveExtractionTaskService.class);

    private final ArchiveService archiveService;
    private final StorageService storageService;
    private final TaskManagerService taskManagerService;
    private final ActivityLogService activityLogService;
    private final ClientIpResolver clientIpResolver;
    private final TemporaryArtifactRegistry temporaryArtifactRegistry;

    public ArchiveExtractionTaskService(
            ArchiveService archiveService,
            StorageService storageService,
            TaskManagerService taskManagerService,
            ActivityLogService activityLogService,
            ClientIpResolver clientIpResolver,
            TemporaryArtifactRegistry temporaryArtifactRegistry
    ) {
        this.archiveService = archiveService;
        this.storageService = storageService;
        this.taskManagerService = taskManagerService;
        this.activityLogService = activityLogService;
        this.clientIpResolver = clientIpResolver;
        this.temporaryArtifactRegistry = temporaryArtifactRegistry;
    }

    public AppTask queue(
            String archivePath,
            String destinationPath,
            String outputName,
            boolean createContainingDirectory,
            ConflictPolicy conflictPolicy,
            HttpServletRequest request
    ) throws IOException {
        Path archive = storageService.resolveVaultFile(archivePath);
        storageService.resolveVaultDirectory(destinationPath);
        ArchiveManifest manifest = archiveService.manifest(archive);
        if (!manifest.extractable()) {
            throw new StorageAccessException(
                    manifest.message().isBlank() ? "This archive cannot be extracted safely." : manifest.message()
            );
        }
        String safeName = outputName == null || outputName.isBlank()
                ? archiveService.suggestedDirectoryName(archive)
                : outputName.trim();
        if (createContainingDirectory) {
            storageService.validateVaultEntryName(safeName);
        }
        ConflictPolicy safePolicy = requireDirectoryPolicy(conflictPolicy);
        List<StorageBatchEntry> topLevelEntries = manifest.children("").stream()
                .map(entry -> new StorageBatchEntry(entry.name(), entry.directory()))
                .toList();
        storageService.preflightArchiveExtraction(
                destinationPath,
                createContainingDirectory,
                safeName,
                topLevelEntries,
                safePolicy
        );
        RequestSnapshot snapshot = RequestSnapshot.from(request, clientIpResolver);

        activityLogService.record(
                "ARCHIVE_EXTRACT_QUEUED",
                snapshot.actor(),
                snapshot.ip(),
                archivePath,
                destinationPath,
                true,
                "Queued archive extraction",
                Map.of(
                        "layout", createContainingDirectory ? "CONTAINING_DIRECTORY" : "DIRECT",
                        "outputName", createContainingDirectory ? safeName : "-",
                        "topLevelItems", Integer.toString(topLevelEntries.size()),
                        "conflictPolicy", safePolicy.value()
                )
        );
        return taskManagerService.submit(
                TaskType.ARCHIVE_EXTRACT,
                "Extract " + archive.getFileName(),
                destinationPath,
                snapshot.actor(),
                snapshot.ip(),
                context -> {
                    Path workspace = null;
                    TemporaryArtifactRegistry.Registration registration = null;
                    try {
                        workspace = storageService.createArchiveExtractionWorkspace();
                        registration = temporaryArtifactRegistry.register(
                                workspace,
                                TemporaryArtifactType.ARCHIVE_EXTRACTION,
                                context.taskId()
                        );
                        Path extractedDirectory = workspace.resolve("content");
                        Files.createDirectory(extractedDirectory);
                        archiveService.extract(archive, extractedDirectory, context);
                        context.checkCanceled();
                        context.message("Committing extracted files.");
                        String committedTarget;
                        int committedCount;
                        if (createContainingDirectory) {
                            FileItem committed = storageService.commitTemporaryDirectoryIntoVault(
                                    extractedDirectory,
                                    destinationPath,
                                    safeName,
                                    safePolicy
                            );
                            committedTarget = committed.path();
                            committedCount = 1;
                        } else {
                            StorageBatchCommitResult committed = storageService.commitArchiveContentsIntoVault(
                                    extractedDirectory,
                                    destinationPath,
                                    topLevelEntries,
                                    safePolicy,
                                    cancellationListener(context)
                            );
                            committedTarget = destinationPath;
                            committedCount = committed.committedCount();
                        }
                        activityLogService.record(
                                "ARCHIVE_EXTRACT_COMPLETE",
                                snapshot.actor(),
                                snapshot.ip(),
                                archivePath,
                                committedTarget,
                                true,
                                "Archive extraction complete",
                                Map.of(
                                        "format", archiveService.requireFormat(archive).id(),
                                        "layout", createContainingDirectory ? "CONTAINING_DIRECTORY" : "DIRECT",
                                        "committedItems", Integer.toString(committedCount)
                                )
                        );
                        return createContainingDirectory
                                ? TaskOutcome.complete("Extracted archive to /" + committedTarget + ".")
                                : TaskOutcome.complete(
                                        "Extracted %d top-level item%s directly to %s."
                                                .formatted(
                                                        committedCount,
                                                        committedCount == 1 ? "" : "s",
                                                        displayVaultPath(committedTarget)
                                                )
                                );
                    } catch (PartialStorageCommitException ex) {
                        recordPartialFailure(archivePath, destinationPath, snapshot, ex);
                        return TaskOutcome.partial(ex.getMessage());
                    } catch (TaskCanceledException ex) {
                        recordFailure("ARCHIVE_EXTRACT_CANCELED", archivePath, destinationPath, snapshot, ex);
                        throw ex;
                    } catch (IOException | RuntimeException ex) {
                        if (context.canceled()) {
                            TaskCanceledException canceled = new TaskCanceledException();
                            recordFailure("ARCHIVE_EXTRACT_CANCELED", archivePath, destinationPath, snapshot, canceled);
                            throw canceled;
                        }
                        recordFailure("ARCHIVE_EXTRACT_FAILED", archivePath, destinationPath, snapshot, ex);
                        throw ex;
                    } finally {
                        cleanup(workspace);
                        close(registration);
                    }
                }
        );
    }

    private StorageProgressListener cancellationListener(TaskContext context) {
        return new StorageProgressListener() {
            @Override
            public void checkCanceled() {
                context.checkCanceled();
            }
        };
    }

    private String displayVaultPath(String vaultPath) {
        return vaultPath == null || vaultPath.isBlank() ? "/" : "/" + vaultPath;
    }

    private ConflictPolicy requireDirectoryPolicy(ConflictPolicy policy) {
        ConflictPolicy effective = policy == null ? ConflictPolicy.CANCEL : policy;
        if (effective == ConflictPolicy.OVERWRITE) {
            throw new StorageAccessException("Archive directory overwrite is not supported. Choose cancel or rename.");
        }
        return effective;
    }

    private void recordFailure(
            String type,
            String archivePath,
            String destinationPath,
            RequestSnapshot request,
            Exception failure
    ) {
        activityLogService.record(
                type,
                request.actor(),
                request.ip(),
                archivePath,
                destinationPath,
                false,
                type.equals("ARCHIVE_EXTRACT_CANCELED") ? "Archive extraction canceled" : "Archive extraction failed",
                Map.of("reason", failure.getClass().getSimpleName())
        );
    }

    private void recordPartialFailure(
            String archivePath,
            String destinationPath,
            RequestSnapshot request,
            PartialStorageCommitException failure
    ) {
        activityLogService.record(
                "ARCHIVE_EXTRACT_FAILED",
                request.actor(),
                request.ip(),
                archivePath,
                destinationPath,
                false,
                "Archive extraction partially committed",
                Map.of(
                        "reason", failure.getClass().getSimpleName(),
                        "unresolvedItems", Integer.toString(failure.remainingVaultPaths().size())
                )
        );
    }

    private void cleanup(Path workspace) {
        if (workspace == null) {
            return;
        }
        try {
            storageService.deleteArchiveExtractionWorkspace(workspace);
        } catch (IOException | RuntimeException ex) {
            logger.warn("Failed to remove archive extraction workspace {}.", workspace.getFileName());
        }
    }

    private void close(TemporaryArtifactRegistry.Registration registration) {
        if (registration != null) {
            registration.close();
        }
    }

    private record RequestSnapshot(String actor, String ip) {
        static RequestSnapshot from(HttpServletRequest request, ClientIpResolver clientIpResolver) {
            Principal principal = request == null ? null : request.getUserPrincipal();
            return new RequestSnapshot(
                    principal == null ? "anonymous" : principal.getName(),
                    request == null ? "-" : clientIpResolver.resolve(request)
            );
        }
    }
}
