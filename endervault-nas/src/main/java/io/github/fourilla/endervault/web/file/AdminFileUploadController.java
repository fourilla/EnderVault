package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.storage.StorageService.StagedUpload;
import io.github.fourilla.endervault.upload.PendingUploadConflictService;
import io.github.fourilla.endervault.upload.PendingUploadConflictService.PendingUploadConflict;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.UploadedFilePayload;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
public class AdminFileUploadController {

    private final StorageService storageService;
    private final ActivityLogService activityLogService;
    private final PendingUploadConflictService pendingUploadConflictService;
    private final NasProperties.Upload uploadProperties;

    public AdminFileUploadController(
            StorageService storageService,
            ActivityLogService activityLogService,
            PendingUploadConflictService pendingUploadConflictService,
            NasProperties nasProperties
    ) {
        this.storageService = storageService;
        this.activityLogService = activityLogService;
        this.pendingUploadConflictService = pendingUploadConflictService;
        this.uploadProperties = nasProperties.getUpload();
    }

    @PostMapping("/files/upload")
    public Object upload(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "conflictPolicy", required = false) String conflictPolicy,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        String redirect = redirectToFiles(path, view, sort, direction, page, size);
        Object validationFailure = validateUploadRequest(files, request, redirectAttributes, redirect);
        if (validationFailure != null) {
            return validationFailure;
        }

        List<UploadedFilePayload> uploadedFiles = new ArrayList<>();
        for (MultipartFile file : files) {
            FileItem uploadedFile;
            try {
                uploadedFile = uploadFile(path, file, conflictPolicy, request, view, sort, direction, page, size);
            } catch (UploadConflictException ex) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.response());
            }
            if (uploadedFile != null) {
                uploadedFiles.add(UploadedFilePayload.from(uploadedFile));
                activityLogService.record(
                        "UPLOAD",
                        request,
                        uploadedFile.path(),
                        null,
                        "Uploaded " + uploadedFile.name(),
                        Map.of("size", uploadedFile.sizeLabel())
                );
            }
        }
        FlashNotification notification = FlashNotification.success("Upload complete.");
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                redirect,
                ActionResponse.ok(notification, uploadedFiles)
        );
    }

    @PostMapping("/files/upload/conflicts/resolve")
    public Object resolveUploadConflict(
            @RequestParam("id") String id,
            @RequestParam(value = "conflictPolicy", required = false) String conflictPolicy,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        PendingUploadConflict conflict = pendingUploadConflictService.resolve(id);
        ConflictPolicy policy = effectiveUploadConflictPolicy(conflictPolicy);
        String redirect = redirectToFiles(conflict.directoryPath(), view, sort, direction, page, size);

        if (policy == ConflictPolicy.CANCEL) {
            Files.deleteIfExists(conflict.temporaryFile());
            activityLogService.record(
                    "UPLOAD",
                    request,
                    targetPath(conflict.directoryPath(), conflict.filename()),
                    null,
                    false,
                    "Upload canceled after file name conflict",
                    Map.of("reason", "conflict-canceled")
            );
            FlashNotification notification = FlashNotification.warning("Upload canceled.");
            return ActionResponseSupport.ok(
                    request,
                    redirectAttributes,
                    notification,
                    redirect,
                    UploadConflictResolveResponse.ok(notification, null, ActionResponseSupport.redirectUrl(redirect))
            );
        }

        FileItem uploadedFile;
        try {
            uploadedFile = storageService.moveStagedUploadIntoVault(
                    new StagedUpload(conflict.temporaryFile(), conflict.filename(), conflict.size()),
                    conflict.directoryPath(),
                    policy
            );
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(conflict.temporaryFile());
            throw ex;
        }
        activityLogService.record(
                "UPLOAD",
                request,
                uploadedFile.path(),
                null,
                "Uploaded " + uploadedFile.name(),
                Map.of("size", uploadedFile.sizeLabel(), "conflictPolicy", policy.value())
        );
        FlashNotification notification = FlashNotification.success("Upload complete.");
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                redirect,
                UploadConflictResolveResponse.ok(
                        notification,
                        UploadedFilePayload.from(uploadedFile),
                        ActionResponseSupport.redirectUrl(redirect)
                )
        );
    }

    private Object validateUploadRequest(
            MultipartFile[] files,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes,
            String redirect
    ) {
        int maxFiles = uploadProperties.getMaxFilesPerRequest();
        int fileCount = files == null ? 0 : files.length;
        if (maxFiles > 0 && fileCount > maxFiles) {
            return ActionResponseSupport.badRequest(
                    request,
                    redirectAttributes,
                    FlashNotification.warning("Upload is limited to " + maxFiles + " file(s) per request."),
                    redirect
            );
        }
        if (!uploadProperties.isDirectoryUploadEnabled() && files != null) {
            for (MultipartFile file : files) {
                String filename = file == null ? "" : file.getOriginalFilename();
                if (filename != null && (filename.contains("/") || filename.contains("\\"))) {
                    return ActionResponseSupport.badRequest(
                            request,
                            redirectAttributes,
                            FlashNotification.warning("Directory upload is disabled."),
                            redirect
                    );
                }
            }
        }
        return null;
    }

    private FileItem uploadFile(
            String path,
            MultipartFile file,
            String conflictPolicy,
            HttpServletRequest request,
            String view,
            String sort,
            String direction,
            Integer page,
            Integer size
    ) throws IOException {
        if (!"ask".equalsIgnoreCase(clean(conflictPolicy)) || !ActionResponseSupport.wantsJson(request)) {
            ConflictPolicy policy = "ask".equalsIgnoreCase(clean(conflictPolicy))
                    ? null
                    : ConflictPolicy.from(conflictPolicy);
            return storageService.upload(path, file, policy);
        }

        StagedUpload stagedUpload = storageService.stageUpload(file);
        if (stagedUpload == null) {
            return null;
        }
        try {
            return storageService.moveStagedUploadIntoVault(stagedUpload, path, ConflictPolicy.CANCEL);
        } catch (FileAlreadyExistsException ex) {
            PendingUploadConflict conflict;
            try {
                conflict = pendingUploadConflictService.create(
                        stagedUpload.temporaryFile(),
                        path,
                        stagedUpload.filename(),
                        stagedUpload.size()
                );
            } catch (IOException | RuntimeException createFailure) {
                Files.deleteIfExists(stagedUpload.temporaryFile());
                throw createFailure;
            }
            FlashNotification notification = FlashNotification.warning("An item with that name already exists.");
            throw new UploadConflictException(new UploadConflictActionResponse(
                    false,
                    notification,
                    UploadConflictPayload.from(conflict, storageService.defaultConflictPolicy().value()),
                    ActionResponseSupport.redirectUrl(redirectToFiles(path, view, sort, direction, page, size))
            ));
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(stagedUpload.temporaryFile());
            throw ex;
        }
    }

    private ConflictPolicy effectiveUploadConflictPolicy(String conflictPolicy) {
        if ("default".equalsIgnoreCase(clean(conflictPolicy))) {
            return storageService.defaultConflictPolicy();
        }
        ConflictPolicy policy = ConflictPolicy.from(conflictPolicy);
        return policy == null ? storageService.defaultConflictPolicy() : policy;
    }

    private String targetPath(String directoryPath, String filename) {
        if (directoryPath == null || directoryPath.isBlank()) {
            return filename;
        }
        return directoryPath + "/" + filename;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String redirectToFiles(
            String path,
            String view,
            String sort,
            String direction,
            Integer page,
            Integer size
    ) {
        int pageNumber = page == null ? 1 : Math.max(1, page);

        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/files");
        if (path != null && !path.isBlank()) {
            builder.queryParam("path", path);
        }
        if (pageNumber > 1) {
            builder.queryParam("page", pageNumber);
        }
        return "redirect:" + builder.build().encode().toUriString();
    }

    private record UploadConflictActionResponse(
            boolean ok,
            FlashNotification notification,
            UploadConflictPayload conflict,
            String redirectUrl
    ) {
    }

    private record UploadConflictPayload(
            String id,
            String fileName,
            String directoryPath,
            String defaultPolicy
    ) {
        static UploadConflictPayload from(PendingUploadConflict conflict, String defaultPolicy) {
            return new UploadConflictPayload(
                    conflict.id(),
                    conflict.filename(),
                    conflict.directoryPath(),
                    defaultPolicy
            );
        }
    }

    private record UploadConflictResolveResponse(
            boolean ok,
            FlashNotification notification,
            UploadedFilePayload uploadedFile,
            String redirectUrl
    ) {
        static UploadConflictResolveResponse ok(
                FlashNotification notification,
                UploadedFilePayload uploadedFile,
                String redirectUrl
        ) {
            return new UploadConflictResolveResponse(true, notification, uploadedFile, redirectUrl);
        }
    }

    private static class UploadConflictException extends RuntimeException {

        private final UploadConflictActionResponse response;

        UploadConflictException(UploadConflictActionResponse response) {
            this.response = response;
        }

        UploadConflictActionResponse response() {
            return response;
        }
    }
}
