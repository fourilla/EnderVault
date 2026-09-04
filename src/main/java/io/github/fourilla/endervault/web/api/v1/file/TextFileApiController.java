package io.github.fourilla.endervault.web.api.v1.file;

import static io.github.fourilla.endervault.web.file.FileRedirects.detailUrl;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.filetool.TextFileContent;
import io.github.fourilla.endervault.filetool.text.TextDraftLeaseException;
import io.github.fourilla.endervault.filetool.text.TextDraftRecoveryResult;
import io.github.fourilla.endervault.filetool.text.TextDraftRecoveryService;
import io.github.fourilla.endervault.filetool.text.TextDraftSaveAsSuggestion;
import io.github.fourilla.endervault.filetool.text.TextDraftService;
import io.github.fourilla.endervault.filetool.text.TextDraftSnapshot;
import io.github.fourilla.endervault.filetool.text.TextDraftSourceConflictException;
import io.github.fourilla.endervault.filetool.text.TextDraftStatus;
import io.github.fourilla.endervault.filetool.text.TextFileService;
import io.github.fourilla.endervault.recent.RecentService;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FileConflictPayload;
import io.github.fourilla.endervault.web.support.FileConflictPolicies;
import io.github.fourilla.endervault.web.support.FileConflictResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.TextDraftResponse;
import io.github.fourilla.endervault.web.support.TextFileLoadResponse;
import io.github.fourilla.endervault.web.support.TextFilePayload;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/files/text")
public class TextFileApiController {

    private final StorageService storageService;
    private final TextFileService textFileService;
    private final TextDraftService textDraftService;
    private final TextDraftRecoveryService textDraftRecoveryService;
    private final RecentService recentService;
    private final ActivityLogService activityLogService;

    public TextFileApiController(
            StorageService storageService,
            TextFileService textFileService,
            TextDraftService textDraftService,
            TextDraftRecoveryService textDraftRecoveryService,
            RecentService recentService,
            ActivityLogService activityLogService
    ) {
        this.storageService = storageService;
        this.textFileService = textFileService;
        this.textDraftService = textDraftService;
        this.textDraftRecoveryService = textDraftRecoveryService;
        this.recentService = recentService;
        this.activityLogService = activityLogService;
    }

    @PostMapping("/save")
    public Object save(
            @RequestParam("path") String path,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam("editorToken") String editorToken,
            @RequestParam(value = "draftId", required = false) String draftId,
            @RequestParam(value = "forceOverwrite", defaultValue = "false") boolean forceOverwrite,
            HttpServletRequest request
    ) throws IOException {
        UUID parsedDraftId = optionalDraftId(draftId);
        if (shouldUseDetachedDraft(parsedDraftId, path)) {
            return missingSourceResponse(path, content, editorToken, parsedDraftId, false);
        }

        FileDetail detail;
        Path file;
        try {
            detail = detailForPath(path);
            file = storageService.resolveVaultFile(detail.path());
        } catch (NoSuchFileException ex) {
            return missingSourceResponse(path, content, editorToken, parsedDraftId, false);
        }

        try {
            textDraftService.saveToSource(detail, file, content, editorToken, forceOverwrite);
        } catch (TextDraftSourceConflictException ex) {
            TextDraftStatus status = textDraftService.status(detail.path(), file, editorToken);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    TextDraftResponse.error("SOURCE_CHANGED", ex.getMessage(), status)
            );
        } catch (TextDraftLeaseException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    TextDraftResponse.error("LEASE_CONFLICT", ex.getMessage(), ex.status())
            );
        }

        recentService.recordVaultPath(detail.path());
        activityLogService.record("TEXT_SAVE", request, detail.path(), null, "Saved text file " + detail.name());
        return TextDraftResponse.saved();
    }

    @GetMapping("/draft")
    public TextDraftResponse draftStatus(
            @RequestParam("path") String path,
            @RequestParam(value = "editorToken", required = false) String editorToken
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        Path file = storageService.resolveVaultFile(detail.path());
        textFileService.requireTextTool(detail);
        return TextDraftResponse.status(textDraftService.status(detail.path(), file, editorToken));
    }

    @PostMapping("/draft")
    public ResponseEntity<TextDraftResponse> autosaveDraft(
            @RequestParam("path") String path,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam("editorToken") String editorToken,
            @RequestParam(value = "draftId", required = false) String draftId,
            @RequestParam(value = "takeOver", defaultValue = "false") boolean takeOver
    ) throws IOException {
        UUID parsedDraftId = optionalDraftId(draftId);
        if (shouldUseDetachedDraft(parsedDraftId, path)) {
            return autosaveDetached(path, content, editorToken, parsedDraftId, takeOver);
        }
        try {
            FileDetail detail = detailForPath(path);
            Path file = storageService.resolveVaultFile(detail.path());
            TextDraftStatus status = textDraftService.autosave(detail, file, content, editorToken, takeOver);
            return ResponseEntity.ok(TextDraftResponse.autosaved(status));
        } catch (NoSuchFileException ex) {
            return autosaveDetached(path, content, editorToken, parsedDraftId, takeOver);
        } catch (TextDraftLeaseException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    TextDraftResponse.error("LEASE_CONFLICT", ex.getMessage(), ex.status())
            );
        }
    }

    @PostMapping("/draft/save-as")
    public ResponseEntity<?> saveDraftAs(
            @RequestParam("path") String path,
            @RequestParam("name") String name,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam("editorToken") String editorToken,
            @RequestParam(value = "draftId", required = false) String draftId,
            @RequestParam(value = "conflictPolicy", defaultValue = "ask") String conflictPolicy,
            HttpServletRequest request
    ) throws IOException {
        if (FileConflictPolicies.cancels(conflictPolicy, storageService.defaultConflictPolicy())) {
            return ResponseEntity.ok(ActionResponse.ok(
                    FlashNotification.warning("Save As canceled. The text draft was retained.")
            ));
        }

        UUID parsedDraftId = optionalDraftId(draftId);
        TextDraftSaveAsSuggestion suggestion = textDraftRecoveryService.suggestion(path);
        ConflictPolicy policy = FileConflictPolicies.mutationPolicy(
                conflictPolicy,
                storageService.defaultConflictPolicy()
        );
        TextDraftRecoveryResult recovery;
        try {
            recovery = textDraftRecoveryService.saveAs(
                    parsedDraftId,
                    editorToken,
                    path,
                    name,
                    content,
                    policy
            );
        } catch (FileAlreadyExistsException ex) {
            if (FileConflictPolicies.asks(conflictPolicy)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(FileConflictResponse.conflict(
                        new FileConflictPayload(
                                "text-save-as",
                                name,
                                suggestion.directoryPath(),
                                storageService.defaultConflictPolicy().value(),
                                "A file with that name already exists in the recovery destination."
                        ),
                        null
                ));
            }
            throw ex;
        } catch (TextDraftLeaseException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    TextDraftResponse.error("LEASE_CONFLICT", ex.getMessage(), ex.status(), suggestion)
            );
        }

        FileItem saved = recovery.file();
        recentService.recordVaultPath(saved.path());
        activityLogService.record(
                "TEXT_SAVE_AS",
                request,
                path,
                saved.path(),
                "Recovered text draft as " + saved.name()
        );
        FlashNotification notification = recovery.originalParentMissing()
                ? FlashNotification.success(
                        "The original parent directory no longer exists. The text draft was saved in "
                                + recoveryDirectoryLabel(saved.parentPath())
                                + "."
                )
                : FlashNotification.success("Text draft saved as a new file.");
        return ResponseEntity.ok(ActionResponse.redirect(notification, detailUrl(saved.path())));
    }

    @PostMapping("/draft/restore")
    public ResponseEntity<TextDraftResponse> restoreDraft(
            @RequestParam("path") String path,
            @RequestParam("editorToken") String editorToken,
            @RequestParam(value = "takeOver", defaultValue = "false") boolean takeOver
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        Path file = storageService.resolveVaultFile(detail.path());
        try {
            TextDraftSnapshot draft = textDraftService.restore(detail, file, editorToken, takeOver);
            return ResponseEntity.ok(TextDraftResponse.restored(draft.status(), draft.content()));
        } catch (TextDraftLeaseException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    TextDraftResponse.error("LEASE_CONFLICT", ex.getMessage(), ex.status())
            );
        }
    }

    @PostMapping("/draft/discard")
    public ResponseEntity<TextDraftResponse> discardDraft(
            @RequestParam("path") String path,
            @RequestParam("editorToken") String editorToken,
            @RequestParam(value = "takeOver", defaultValue = "false") boolean takeOver
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        Path file = storageService.resolveVaultFile(detail.path());
        try {
            textDraftService.discard(detail.path(), file, editorToken, takeOver);
            return ResponseEntity.ok(TextDraftResponse.discarded());
        } catch (TextDraftLeaseException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    TextDraftResponse.error("LEASE_CONFLICT", ex.getMessage(), ex.status())
            );
        }
    }

    @GetMapping("/load")
    public TextFileLoadResponse load(@RequestParam("path") String path) throws IOException {
        FileDetail detail = detailForPath(path);
        Path file = storageService.resolveVaultFile(detail.path());
        TextFileContent content = textFileService.loadText(detail, file);
        if (!content.loaded()) {
            throw new StorageAccessException(content.message());
        }
        recentService.recordVaultPath(detail.path());
        return TextFileLoadResponse.ok(TextFilePayload.from(content));
    }

    private FileDetail detailForPath(String path) throws IOException {
        if (path == null || path.isBlank() || "/".equals(path)) {
            throw new NoSuchFileException("");
        }
        return storageService.detail(StorageScope.VAULT, path);
    }

    private ResponseEntity<TextDraftResponse> autosaveDetached(
            String path,
            String content,
            String editorToken,
            UUID draftId,
            boolean takeOver
    ) throws IOException {
        TextDraftSaveAsSuggestion suggestion = textDraftRecoveryService.suggestion(path);
        if (draftId == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(TextDraftResponse.error(
                    "SOURCE_MISSING",
                    "The original file is missing. Save the current text as a new file.",
                    TextDraftStatus.missing(),
                    suggestion
            ));
        }
        try {
            TextDraftStatus status = textDraftService.autosaveDetached(
                    draftId,
                    content,
                    editorToken,
                    takeOver
            );
            return ResponseEntity.ok(TextDraftResponse.autosavedDetached(status, suggestion));
        } catch (TextDraftLeaseException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    TextDraftResponse.error("LEASE_CONFLICT", ex.getMessage(), ex.status(), suggestion)
            );
        }
    }

    private ResponseEntity<TextDraftResponse> missingSourceResponse(
            String path,
            String content,
            String editorToken,
            UUID draftId,
            boolean takeOver
    ) throws IOException {
        TextDraftSaveAsSuggestion suggestion = textDraftRecoveryService.suggestion(path);
        TextDraftStatus status = TextDraftStatus.missing();
        if (draftId != null) {
            try {
                status = textDraftService.autosaveDetached(draftId, content, editorToken, takeOver);
            } catch (TextDraftLeaseException ex) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(
                        TextDraftResponse.error("LEASE_CONFLICT", ex.getMessage(), ex.status(), suggestion)
                );
            }
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(TextDraftResponse.error(
                "SOURCE_MISSING",
                "The original file is missing. Save the current text as a new file.",
                status,
                suggestion
        ));
    }

    private boolean shouldUseDetachedDraft(UUID draftId, String path) throws IOException {
        return draftId != null && !textDraftService.matchesVaultPath(draftId, path);
    }

    private UUID optionalDraftId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new StorageAccessException("Text draft identifier is invalid.", ex);
        }
    }

    private String recoveryDirectoryLabel(String directoryPath) {
        return directoryPath == null || directoryPath.isBlank()
                ? "the vault root"
                : "/" + directoryPath;
    }
}
