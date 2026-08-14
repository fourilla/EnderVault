package io.github.fourilla.endervault.filetool.text;

import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import io.github.fourilla.endervault.temporary.TemporaryArtifactType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TextDraftRecoveryService {

    private static final Logger logger = LoggerFactory.getLogger(TextDraftRecoveryService.class);
    private static final int MAX_FILENAME_LENGTH = 255;

    private final TextDraftService textDraftService;
    private final TextFileService textFileService;
    private final StorageService storageService;
    private final TemporaryArtifactRegistry temporaryArtifactRegistry;

    public TextDraftRecoveryService(
            TextDraftService textDraftService,
            TextFileService textFileService,
            StorageService storageService,
            TemporaryArtifactRegistry temporaryArtifactRegistry
    ) {
        this.textDraftService = textDraftService;
        this.textFileService = textFileService;
        this.storageService = storageService;
        this.temporaryArtifactRegistry = temporaryArtifactRegistry;
    }

    public TextDraftSaveAsSuggestion suggestion(String originalPath) throws IOException {
        return new TextDraftSaveAsSuggestion(
                nearestExistingParent(originalPath).directoryPath(),
                recoveredFilename(fileNameOf(originalPath))
        );
    }

    public TextDraftRecoveryResult saveAs(
            UUID draftId,
            String editorToken,
            String originalPath,
            String requestedName,
            String fallbackContent,
            ConflictPolicy conflictPolicy
    ) throws IOException {
        String content = fallbackContent;
        TextDraftSnapshot claimedDraft = null;
        if (draftId != null) {
            claimedDraft = textDraftService.claimDetached(draftId, editorToken);
            content = claimedDraft.content();
        }
        byte[] bytes = textFileService.validatedBytes(content);
        RecoveryDirectory recoveryDirectory = nearestExistingParent(originalPath);
        Path temporaryFile = storageService.createFileStagingTemporaryFile("text-recovery-", ".tmp");
        TemporaryArtifactRegistry.Registration registration = temporaryArtifactRegistry.register(
                temporaryFile,
                TemporaryArtifactType.TEXT_RECOVERY,
                draftId == null ? originalPath : draftId.toString()
        );
        try {
            Files.write(
                    temporaryFile,
                    bytes,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            FileItem saved = storageService.moveTemporaryFileIntoVault(
                    temporaryFile,
                    recoveryDirectory.directoryPath(),
                    requestedName,
                    conflictPolicy
            );
            temporaryFile = null;
            deleteDraftAfterRecovery(draftId, editorToken, claimedDraft);
            return new TextDraftRecoveryResult(saved, recoveryDirectory.fallback());
        } finally {
            try {
                if (temporaryFile != null) {
                    Files.deleteIfExists(temporaryFile);
                }
            } finally {
                registration.close();
            }
        }
    }

    private RecoveryDirectory nearestExistingParent(String originalPath) throws IOException {
        String originalParent = parentPathOf(originalPath);
        String candidate = originalParent;
        while (true) {
            try {
                storageService.ensureVaultDirectory(candidate);
                return new RecoveryDirectory(candidate, !candidate.equals(originalParent));
            } catch (NoSuchFileException ex) {
                if (candidate.isEmpty()) {
                    throw ex;
                }
                candidate = parentPathOf(candidate);
            }
        }
    }

    private void deleteDraftAfterRecovery(
            UUID draftId,
            String editorToken,
            TextDraftSnapshot claimedDraft
    ) {
        if (draftId == null || claimedDraft == null) {
            return;
        }
        try {
            boolean deleted = textDraftService.deleteIfUnchanged(
                    draftId,
                    editorToken,
                    claimedDraft.status().revision()
            );
            if (!deleted) {
                logger.warn(
                        "Recovered text file was saved, but a newer draft version was retained. draftId={}",
                        draftId
                );
            }
        } catch (IOException ex) {
            logger.warn(
                    "Recovered text file was saved, but draft cleanup failed. draftId={}",
                    draftId,
                    ex
            );
        }
    }

    private String parentPathOf(String path) {
        String normalized = path == null ? "" : path.trim().replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index < 0 ? "" : normalized.substring(0, index);
    }

    private String fileNameOf(String path) {
        String normalized = path == null ? "" : path.trim().replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    private String recoveredFilename(String originalName) {
        String cleanName = originalName == null || originalName.isBlank() ? "recovered.txt" : originalName;
        int extensionIndex = cleanName.lastIndexOf('.');
        String stem = extensionIndex > 0 ? cleanName.substring(0, extensionIndex) : cleanName;
        String extension = extensionIndex > 0 ? cleanName.substring(extensionIndex) : "";
        String suffix = " - recovered";
        int maxStemLength = MAX_FILENAME_LENGTH - suffix.length() - extension.length();
        if (maxStemLength < 1) {
            return "recovered.txt";
        }
        String trimmedStem = stem.length() > maxStemLength ? stem.substring(0, maxStemLength) : stem;
        return trimmedStem + suffix + extension;
    }

    private record RecoveryDirectory(
            String directoryPath,
            boolean fallback
    ) {
    }
}
