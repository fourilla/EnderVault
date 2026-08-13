package io.github.fourilla.endervault.filetool.archive;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.auth.ClientIpResolver;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageOperationSummary;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.task.TaskCanceledException;
import io.github.fourilla.endervault.task.TaskContext;
import io.github.fourilla.endervault.task.TaskManagerService;
import io.github.fourilla.endervault.task.TaskOutcome;
import io.github.fourilla.endervault.task.TaskType;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.Principal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ArchiveCreationTaskService {

    private static final Logger logger = LoggerFactory.getLogger(ArchiveCreationTaskService.class);

    private final StorageService storageService;
    private final TaskManagerService taskManagerService;
    private final ActivityLogService activityLogService;
    private final ClientIpResolver clientIpResolver;

    public ArchiveCreationTaskService(
            StorageService storageService,
            TaskManagerService taskManagerService,
            ActivityLogService activityLogService,
            ClientIpResolver clientIpResolver
    ) {
        this.storageService = storageService;
        this.taskManagerService = taskManagerService;
        this.activityLogService = activityLogService;
        this.clientIpResolver = clientIpResolver;
    }

    public AppTask queue(
            String directoryPath,
            List<String> itemNames,
            String outputName,
            ConflictPolicy conflictPolicy,
            HttpServletRequest request
    ) throws IOException {
        List<String> selectedNames = normalizeSelectedNames(itemNames);
        List<FileItem> selectedItems = selectedNames.stream()
                .map(name -> describe(directoryPath, name))
                .toList();
        String archiveName = normalizeArchiveName(outputName, selectedItems);
        ConflictPolicy safePolicy = requireCreationPolicy(conflictPolicy);
        storageService.validateVaultEntryName(archiveName);
        storageService.preflightVaultFileCommit(directoryPath, archiveName, safePolicy);

        List<String> sourcePaths = selectedItems.stream().map(FileItem::path).toList();
        RequestSnapshot snapshot = RequestSnapshot.from(request, clientIpResolver);
        String requestedTarget = childPath(directoryPath, archiveName);
        activityLogService.record(
                "ARCHIVE_CREATE_QUEUED",
                snapshot.actor(),
                snapshot.ip(),
                directoryPath,
                requestedTarget,
                true,
                "Queued ZIP archive creation",
                Map.of(
                        "selectedItems", Integer.toString(selectedNames.size()),
                        "conflictPolicy", safePolicy.value()
                )
        );

        return taskManagerService.submit(
                TaskType.ARCHIVE_CREATE,
                "Create " + archiveName,
                requestedTarget,
                snapshot.actor(),
                snapshot.ip(),
                context -> createArchive(
                        directoryPath,
                        selectedNames,
                        sourcePaths,
                        archiveName,
                        safePolicy,
                        snapshot,
                        context
                )
        );
    }

    private TaskOutcome createArchive(
            String directoryPath,
            List<String> selectedNames,
            List<String> sourcePaths,
            String archiveName,
            ConflictPolicy conflictPolicy,
            RequestSnapshot request,
            TaskContext context
    ) throws IOException {
        Path workspace = null;
        try {
            context.message("Scanning selected items.");
            StorageOperationSummary summary = storageService.summarizeVaultPaths(sourcePaths);
            context.setTotalBytes(summary.totalBytes());
            context.setTotalItems(summary.totalItems());
            context.checkCanceled();

            workspace = storageService.createArchiveCreationWorkspace();
            Path temporaryArchive = workspace.resolve("archive.zip");
            context.message("Compressing selected items.");
            try (OutputStream outputStream = Files.newOutputStream(
                    temporaryArchive,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            )) {
                storageService.writeZip(
                        StorageScope.VAULT,
                        directoryPath,
                        selectedNames,
                        outputStream,
                        progressListener(context)
                );
            }

            context.checkCanceled();
            context.message("Saving ZIP archive.");
            long archiveBytes = Files.size(temporaryArchive);
            StorageService.CommittedVaultFile committed = storageService.commitTemporaryFileIntoVault(
                    temporaryArchive,
                    directoryPath,
                    archiveName,
                    conflictPolicy
            );
            context.targetPath(committed.path());
            activityLogService.record(
                    "ARCHIVE_CREATE_COMPLETE",
                    request.actor(),
                    request.ip(),
                    directoryPath,
                    committed.path(),
                    true,
                    "ZIP archive creation complete",
                    Map.of(
                            "selectedItems", Integer.toString(selectedNames.size()),
                            "sourceBytes", Long.toString(summary.totalBytes()),
                            "archiveBytes", Long.toString(archiveBytes)
                    )
            );
            return TaskOutcome.complete("Created /" + committed.path() + ".");
        } catch (TaskCanceledException ex) {
            recordFailure("ARCHIVE_CREATE_CANCELED", directoryPath, archiveName, request, ex);
            throw ex;
        } catch (IOException | RuntimeException ex) {
            if (context.canceled()) {
                TaskCanceledException canceled = new TaskCanceledException();
                recordFailure("ARCHIVE_CREATE_CANCELED", directoryPath, archiveName, request, canceled);
                throw canceled;
            }
            recordFailure("ARCHIVE_CREATE_FAILED", directoryPath, archiveName, request, ex);
            throw ex;
        } finally {
            cleanup(workspace);
        }
    }

    private List<String> normalizeSelectedNames(List<String> itemNames) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (itemNames != null) {
            itemNames.stream()
                    .filter(name -> name != null && !name.isBlank())
                    .forEach(selected::add);
        }
        if (selected.isEmpty()) {
            throw new StorageAccessException("Select at least one item to compress.");
        }
        return List.copyOf(selected);
    }

    private FileItem describe(String directoryPath, String itemName) {
        try {
            return storageService.describeVaultChild(directoryPath, itemName);
        } catch (IOException ex) {
            throw new StorageAccessException("A selected item is no longer available: " + itemName, ex);
        }
    }

    private String normalizeArchiveName(String outputName, List<FileItem> selectedItems) {
        String name = outputName == null || outputName.isBlank()
                ? suggestedArchiveName(selectedItems)
                : outputName.trim();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            name += ".zip";
        }
        return name;
    }

    private String suggestedArchiveName(List<FileItem> selectedItems) {
        if (selectedItems.size() != 1) {
            return "Archive.zip";
        }
        FileItem item = selectedItems.get(0);
        String stem = item.directory() ? item.name() : removeFileExtension(item.name());
        String suggested = stem + ".zip";
        if (suggested.equalsIgnoreCase(item.name())) {
            return stem + " - compressed.zip";
        }
        return suggested;
    }

    private String removeFileExtension(String filename) {
        int extensionIndex = filename.lastIndexOf('.');
        return extensionIndex > 0 ? filename.substring(0, extensionIndex) : filename;
    }

    private ConflictPolicy requireCreationPolicy(ConflictPolicy policy) {
        ConflictPolicy effective = policy == null ? ConflictPolicy.CANCEL : policy;
        if (effective == ConflictPolicy.OVERWRITE) {
            throw new StorageAccessException("ZIP creation supports cancel or rename only.");
        }
        return effective;
    }

    private StorageProgressListener progressListener(TaskContext context) {
        return new StorageProgressListener() {
            @Override
            public void checkCanceled() {
                context.checkCanceled();
            }

            @Override
            public void onBytesProcessed(long bytes) {
                context.addProcessedBytes(bytes);
            }

            @Override
            public void onItemProcessed() {
                context.incrementProcessedItems();
            }
        };
    }

    private void recordFailure(
            String type,
            String directoryPath,
            String archiveName,
            RequestSnapshot request,
            Exception failure
    ) {
        activityLogService.record(
                type,
                request.actor(),
                request.ip(),
                directoryPath,
                childPath(directoryPath, archiveName),
                false,
                type.equals("ARCHIVE_CREATE_CANCELED")
                        ? "ZIP archive creation canceled"
                        : "ZIP archive creation failed",
                Map.of("reason", failure.getClass().getSimpleName())
        );
    }

    private String childPath(String directoryPath, String name) {
        return directoryPath == null || directoryPath.isBlank() ? name : directoryPath + "/" + name;
    }

    private void cleanup(Path workspace) {
        if (workspace == null) {
            return;
        }
        try {
            storageService.deleteArchiveCreationWorkspace(workspace);
        } catch (IOException | RuntimeException ex) {
            logger.warn("Failed to remove archive creation workspace {}.", workspace.getFileName());
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
