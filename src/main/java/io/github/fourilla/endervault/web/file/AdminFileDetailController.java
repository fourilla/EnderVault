package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.filetool.FileToolDescriptor;
import io.github.fourilla.endervault.filetool.FileToolService;
import io.github.fourilla.endervault.filetool.TextFileContent;
import io.github.fourilla.endervault.filetool.archive.ArchiveFormat;
import io.github.fourilla.endervault.filetool.comic.ComicArchiveManifest;
import io.github.fourilla.endervault.filetool.comic.ComicArchiveService;
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
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.transfer.TransferBufferService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FileConflictPayload;
import io.github.fourilla.endervault.web.support.FileConflictPolicies;
import io.github.fourilla.endervault.web.support.FileConflictResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.ShareLinkView;
import io.github.fourilla.endervault.web.support.ShareUrlBuilder;
import io.github.fourilla.endervault.web.support.TextDraftResponse;
import io.github.fourilla.endervault.web.support.TextFileLoadResponse;
import io.github.fourilla.endervault.web.support.TextFilePayload;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriUtils;

@Controller
public class AdminFileDetailController {

    private final StorageService storageService;
    private final ShareLinkService shareLinkService;
    private final FavoriteService favoriteService;
    private final FileToolService fileToolService;
    private final TextFileService textFileService;
    private final TextDraftService textDraftService;
    private final TextDraftRecoveryService textDraftRecoveryService;
    private final ComicArchiveService comicArchiveService;
    private final RecentService recentService;
    private final ActivityLogService activityLogService;
    private final TransferBufferService transferBufferService;
    private final ShareUrlBuilder shareUrlBuilder;

    public AdminFileDetailController(
            StorageService storageService,
            ShareLinkService shareLinkService,
            FavoriteService favoriteService,
            FileToolService fileToolService,
            TextFileService textFileService,
            TextDraftService textDraftService,
            TextDraftRecoveryService textDraftRecoveryService,
            ComicArchiveService comicArchiveService,
            RecentService recentService,
            ActivityLogService activityLogService,
            TransferBufferService transferBufferService,
            ShareUrlBuilder shareUrlBuilder
    ) {
        this.storageService = storageService;
        this.shareLinkService = shareLinkService;
        this.favoriteService = favoriteService;
        this.fileToolService = fileToolService;
        this.textFileService = textFileService;
        this.textDraftService = textDraftService;
        this.textDraftRecoveryService = textDraftRecoveryService;
        this.comicArchiveService = comicArchiveService;
        this.recentService = recentService;
        this.activityLogService = activityLogService;
        this.transferBufferService = transferBufferService;
        this.shareUrlBuilder = shareUrlBuilder;
    }

    @GetMapping("/files/detail")
    public String detail(
            @RequestParam("path") String path,
            @RequestParam(value = "comicPage", required = false) Integer comicPage,
            HttpServletRequest request,
            Model model
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        recentService.recordVaultPath(detail.path());
        FileToolDescriptor fileTool = fileToolService.resolve(detail);
        model.addAttribute("detail", detail);
        model.addAttribute("fileTool", fileTool);
        if (fileTool.text()) {
            model.addAttribute("textContent", textFileService.readText(detail, storageService.resolveVaultFile(detail.path())));
        }
        if (fileTool.comic()) {
            ComicArchiveManifest comicManifest = comicArchiveService.manifest(storageService.resolveVaultFile(detail.path()));
            int comicPageIndex = comicArchiveService.normalizePage(comicManifest, comicPage);
            int comicPageNumber = comicManifest.empty() ? 0 : comicPageIndex + 1;
            model.addAttribute("comicManifest", comicManifest);
            model.addAttribute("comicPageIndex", comicPageIndex);
            model.addAttribute("comicPageNumber", comicPageNumber);
            model.addAttribute("comicPreviousPageNumber", Math.max(1, comicPageNumber - 1));
            model.addAttribute("comicNextPageNumber", Math.min(comicManifest.pageCount(), comicPageNumber + 1));
        }
        if (fileTool.archive()) {
            ArchiveFormat archiveFormat = ArchiveFormat.fromFilename(detail.name()).orElseThrow();
            model.addAttribute("archiveSuggestedName", archiveFormat.suggestedDirectoryName(detail.name()));
            model.addAttribute("archiveDestinationPath", detail.parentPath());
        }
        String shareBaseUrl = shareUrlBuilder.shareBaseUrl();
        model.addAttribute("shares", shareLinkService.listForVaultPath(detail.path())
                .stream()
                .map(shareLink -> ShareLinkView.from(shareLink, shareBaseUrl, shareUrlBuilder.directDownloadLinkEnabled()))
                .toList());
        model.addAttribute("favorite", favoriteService.isFavorite(detail.path()));
        model.addAttribute("transferBuffer", transferBufferService.current(request.getSession(false)));
        return "file-detail";
    }

    @PostMapping("/files/detail/text")
    public Object saveTextFromDetail(
            @RequestParam("path") String path,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "editorToken", required = false) String editorToken,
            @RequestParam(value = "draftId", required = false) String draftId,
            @RequestParam(value = "forceOverwrite", defaultValue = "false") boolean forceOverwrite,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        String savedPath;
        if (ActionResponseSupport.wantsJson(request) && editorToken != null && !editorToken.isBlank()) {
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
            savedPath = detail.path();
        } else {
            FileDetail detail = detailForPath(path);
            Path file = storageService.resolveVaultFile(detail.path());
            textDraftService.saveDirect(detail, file, content);
            recentService.recordVaultPath(detail.path());
            activityLogService.record("TEXT_SAVE", request, detail.path(), null, "Saved text file " + detail.name());
            savedPath = detail.path();
        }
        FlashNotification notification = FlashNotification.success("Text file saved.");
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                redirectToDetail(savedPath),
                TextDraftResponse.saved()
        );
    }

    @GetMapping("/files/detail/text/draft")
    public ResponseEntity<TextDraftResponse> textDraftStatus(
            @RequestParam("path") String path,
            @RequestParam(value = "editorToken", required = false) String editorToken
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        Path file = storageService.resolveVaultFile(detail.path());
        textFileService.requireTextTool(detail);
        return ResponseEntity.ok(TextDraftResponse.status(
                textDraftService.status(detail.path(), file, editorToken)
        ));
    }

    @PostMapping("/files/detail/text/draft")
    public ResponseEntity<TextDraftResponse> autosaveTextDraft(
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

    @PostMapping("/files/detail/text/draft/save-as")
    public ResponseEntity<?> saveTextDraftAs(
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
        return ResponseEntity.ok(ActionResponse.redirect(
                notification,
                ActionResponseSupport.redirectUrl(redirectToDetail(saved.path()))
        ));
    }

    @PostMapping("/files/detail/text/draft/restore")
    public ResponseEntity<TextDraftResponse> restoreTextDraft(
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

    @PostMapping("/files/detail/text/draft/discard")
    public ResponseEntity<TextDraftResponse> discardTextDraft(
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

    @GetMapping("/files/detail/text/load")
    public ResponseEntity<TextFileLoadResponse> loadTextFromDetail(@RequestParam("path") String path) throws IOException {
        FileDetail detail = detailForPath(path);
        Path file = storageService.resolveVaultFile(detail.path());
        TextFileContent content = textFileService.loadText(detail, file);
        if (!content.loaded()) {
            throw new StorageAccessException(content.message());
        }
        TextFilePayload payload = TextFilePayload.from(content);
        recentService.recordVaultPath(detail.path());
        return ResponseEntity.ok(TextFileLoadResponse.ok(payload));
    }

    private FileDetail detailForPath(String path) throws IOException {
        if (path == null || path.isBlank() || "/".equals(path)) {
            throw new NoSuchFileException("");
        }
        return storageService.detail(StorageScope.VAULT, path);
    }

    private String redirectToDetail(String path) {
        return "redirect:/files/detail?path=" + UriUtils.encodeQueryParam(path, StandardCharsets.UTF_8);
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
