package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.FileLifecycleService;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.storage.StorageService.StagedUpload;
import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.task.FileOperationTaskService;
import io.github.fourilla.endervault.trash.TrashRecord;
import io.github.fourilla.endervault.trash.TrashService;
import io.github.fourilla.endervault.upload.PendingUploadConflictService;
import io.github.fourilla.endervault.upload.PendingUploadConflictService.PendingUploadConflict;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FileConflictPayload;
import io.github.fourilla.endervault.web.support.FileConflictResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.SelectedItems;
import io.github.fourilla.endervault.web.support.UploadedFilePayload;
import io.github.fourilla.endervault.web.task.TaskPayload;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
import org.springframework.web.util.UriUtils;

@Controller
public class AdminFileMutationController {

    private final StorageService storageService;
    private final FileLifecycleService fileLifecycleService;
    private final TrashService trashService;
    private final ActivityLogService activityLogService;
    private final FileOperationTaskService fileOperationTaskService;
    private final PendingUploadConflictService pendingUploadConflictService;
    private final NasProperties.Upload uploadProperties;

    public AdminFileMutationController(
            StorageService storageService,
            FileLifecycleService fileLifecycleService,
            TrashService trashService,
            ActivityLogService activityLogService,
            FileOperationTaskService fileOperationTaskService,
            PendingUploadConflictService pendingUploadConflictService,
            NasProperties nasProperties
    ) {
        this.storageService = storageService;
        this.fileLifecycleService = fileLifecycleService;
        this.trashService = trashService;
        this.activityLogService = activityLogService;
        this.fileOperationTaskService = fileOperationTaskService;
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
        Object validationFailure = validateUploadRequest(files, request, redirectAttributes, redirectToFiles(path, view, sort, direction, page, size));
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
                redirectToFiles(path, view, sort, direction, page, size),
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

    @PostMapping("/files/directories")
    public Object createDirectory(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("name") String name,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        storageService.createDirectory(path, name);
        FileItem directory = storageService.describeVaultChild(path, name);
        activityLogService.record("CREATE_DIRECTORY", request, directory.path(), null, "Created directory " + name);
        FlashNotification notification = FlashNotification.success("Directory created.");
        String redirect = redirectToFiles(path, view, sort, direction, 1, size);
        return ActionResponseSupport.redirect(request, redirectAttributes, notification, redirect);
    }

    @PostMapping("/files/rename")
    public Object rename(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("item") String item,
            @RequestParam("newName") String newName,
            @RequestParam(value = "conflictPolicy", required = false) String conflictPolicy,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        if (isCancelConflictPolicy(conflictPolicy)) {
            return canceledMutation(request, redirectAttributes, redirectToFiles(path));
        }
        FileItem oldItem = storageService.describeVaultChild(path, item);
        String newPath;
        try {
            newPath = storageService.rename(path, item, newName, mutationPolicy(conflictPolicy));
        } catch (FileAlreadyExistsException ex) {
            if (asksConflictPolicy(conflictPolicy, request)) {
                return conflictResponse(
                        "rename",
                        oldItem.name(),
                        targetPath(path, newName),
                        "An item named \"" + newName + "\" already exists.",
                        redirectToFiles(path)
                );
            }
            throw ex;
        }
        FileItem newItem = storageService.describeVaultPath(newPath);
        fileLifecycleService.recordRename(request, oldItem.path(), newItem.path(), "Renamed item to " + newItem.name());
        FlashNotification notification = FlashNotification.success("Item renamed.");
        String redirect = redirectToFiles(path);
        return ActionResponseSupport.redirect(request, redirectAttributes, notification, redirect);
    }

    @PostMapping("/files/detail/rename")
    public Object renameFromDetail(
            @RequestParam("path") String path,
            @RequestParam("newName") String newName,
            @RequestParam(value = "conflictPolicy", required = false) String conflictPolicy,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        if (isCancelConflictPolicy(conflictPolicy)) {
            return canceledMutation(request, redirectAttributes, redirectToDetail(path));
        }
        FileDetail detail = detailForPath(path);
        String newPath;
        try {
            newPath = storageService.renameVaultPath(detail.path(), newName, mutationPolicy(conflictPolicy));
        } catch (FileAlreadyExistsException ex) {
            if (asksConflictPolicy(conflictPolicy, request)) {
                return conflictResponse(
                        "rename",
                        detail.name(),
                        targetPath(detail.parentPath(), newName),
                        "An item named \"" + newName + "\" already exists.",
                        redirectToDetail(path)
                );
            }
            throw ex;
        }
        fileLifecycleService.recordRename(request, detail.path(), newPath, "Renamed item to " + newName);
        FlashNotification notification = FlashNotification.success("Item renamed.");
        String redirect = redirectToDetail(newPath);
        return ActionResponseSupport.redirect(request, redirectAttributes, notification, redirect);
    }

    @PostMapping("/files/move")
    public Object move(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("item") String item,
            @RequestParam("targetPath") String targetPath,
            @RequestParam(value = "conflictPolicy", required = false) String conflictPolicy,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        if (isCancelConflictPolicy(conflictPolicy)) {
            return canceledMutation(request, redirectAttributes, redirectToFiles(path));
        }
        FileItem oldItem = storageService.describeVaultChild(path, item);
        String newPath;
        try {
            newPath = storageService.move(path, item, targetPath, mutationPolicy(conflictPolicy));
        } catch (FileAlreadyExistsException ex) {
            if (asksConflictPolicy(conflictPolicy, request)) {
                return conflictResponse(
                        "move",
                        oldItem.name(),
                        targetPath(targetPath, oldItem.name()),
                        "An item named \"" + oldItem.name() + "\" already exists in the target directory.",
                        redirectToFiles(path)
                );
            }
            throw ex;
        }
        FileItem newItem = storageService.describeVaultPath(newPath);
        fileLifecycleService.recordMove(request, oldItem.path(), newItem.path(), "Moved item to " + targetPath);
        FlashNotification notification = FlashNotification.success("Item moved.");
        String redirect = redirectToFiles(path);
        return ActionResponseSupport.redirect(request, redirectAttributes, notification, redirect);
    }

    @PostMapping("/files/detail/move")
    public Object moveFromDetail(
            @RequestParam("path") String path,
            @RequestParam("targetPath") String targetPath,
            @RequestParam(value = "conflictPolicy", required = false) String conflictPolicy,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        if (isCancelConflictPolicy(conflictPolicy)) {
            return canceledMutation(request, redirectAttributes, redirectToDetail(path));
        }
        FileDetail detail = detailForPath(path);
        String newPath;
        try {
            newPath = storageService.moveVaultPath(detail.path(), targetPath, mutationPolicy(conflictPolicy));
        } catch (FileAlreadyExistsException ex) {
            if (asksConflictPolicy(conflictPolicy, request)) {
                return conflictResponse(
                        "move",
                        detail.name(),
                        targetPath(targetPath, detail.name()),
                        "An item named \"" + detail.name() + "\" already exists in the target directory.",
                        redirectToDetail(path)
                );
            }
            throw ex;
        }
        fileLifecycleService.recordMove(request, detail.path(), newPath, "Moved item to " + targetPath);
        FlashNotification notification = FlashNotification.success("Item moved.");
        String redirect = redirectToDetail(newPath);
        return ActionResponseSupport.redirect(request, redirectAttributes, notification, redirect);
    }

    @PostMapping("/files/delete")
    public Object delete(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        List<String> items = SelectedItems.from(request);
        if (items.isEmpty()) {
            return ActionResponseSupport.badRequest(
                    request,
                    redirectAttributes,
                    FlashNotification.warning("Select at least one item."),
                    redirectToFiles(path, view, sort, direction, page, size)
            );
        }
        String redirect = redirectToFiles(path, view, sort, direction, page, size);
        if (ActionResponseSupport.wantsJson(request)) {
            AppTask task = fileOperationTaskService.queueMoveToTrash(path, items, request);
            FlashNotification notification = FlashNotification.info("Trash task queued.");
            return ResponseEntity.accepted().body(new FileTaskActionResponse(
                    true,
                    notification,
                    TaskPayload.from(task),
                    ActionResponseSupport.redirectUrl(redirect)
            ));
        }
        List<TrashRecord> trashRecords = trashService.moveToTrash(path, items);
        for (TrashRecord record : trashRecords) {
            activityLogService.record("TRASH_MOVE", request, record.originalPath(), null, "Moved item to trash");
        }
        FlashNotification notification = FlashNotification.success("Selected items moved to trash.");
        return ActionResponseSupport.redirect(request, redirectAttributes, notification, redirect);
    }

    @PostMapping("/files/detail/delete")
    public Object deleteFromDetail(
            @RequestParam("path") String path,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        TrashRecord record = trashService.moveVaultPathToTrash(detail.path());
        activityLogService.record("TRASH_MOVE", request, record.originalPath(), null, "Moved item to trash");
        FlashNotification notification = FlashNotification.success("Item moved to trash.");
        String redirect = redirectToFiles(detail.parentPath());
        return ActionResponseSupport.redirect(request, redirectAttributes, notification, redirect);
    }

    private FileDetail detailForPath(String path) throws IOException {
        if (path == null || path.isBlank() || "/".equals(path)) {
            throw new NoSuchFileException("");
        }
        return storageService.detail(StorageScope.VAULT, path);
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

    private ConflictPolicy mutationPolicy(String conflictPolicy) {
        if (asksConflictPolicy(conflictPolicy)) {
            return ConflictPolicy.CANCEL;
        }
        if ("default".equalsIgnoreCase(clean(conflictPolicy))) {
            return storageService.defaultConflictPolicy();
        }
        return ConflictPolicy.from(conflictPolicy);
    }

    private boolean asksConflictPolicy(String conflictPolicy, HttpServletRequest request) {
        return asksConflictPolicy(conflictPolicy) && ActionResponseSupport.wantsJson(request);
    }

    private boolean asksConflictPolicy(String conflictPolicy) {
        return "ask".equalsIgnoreCase(clean(conflictPolicy));
    }

    private boolean isCancelConflictPolicy(String conflictPolicy) {
        String cleanPolicy = clean(conflictPolicy);
        return "cancel".equalsIgnoreCase(cleanPolicy)
                || ("default".equalsIgnoreCase(cleanPolicy)
                && storageService.defaultConflictPolicy() == ConflictPolicy.CANCEL);
    }

    private Object canceledMutation(
            HttpServletRequest request,
            RedirectAttributes redirectAttributes,
            String redirect
    ) {
        FlashNotification notification = FlashNotification.warning("Action canceled.");
        return ActionResponseSupport.ok(request, redirectAttributes, notification, redirect, ActionResponse.redirect(
                notification,
                ActionResponseSupport.redirectUrl(redirect)
        ));
    }

    private ResponseEntity<FileConflictResponse> conflictResponse(
            String operation,
            String itemName,
            String targetPath,
            String message,
            String redirect
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(FileConflictResponse.conflict(
                new FileConflictPayload(
                        operation,
                        itemName,
                        targetPath,
                        storageService.defaultConflictPolicy().value(),
                        message
                ),
                ActionResponseSupport.redirectUrl(redirect)
        ));
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

    private String redirectToFiles(String path) {
        return redirectToFiles(path, null);
    }

    private String redirectToFiles(String path, String view) {
        return redirectToFiles(path, view, null, null, null, null);
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

    private String redirectToDetail(String path) {
        return "redirect:/files/detail?path=" + UriUtils.encodeQueryParam(path, StandardCharsets.UTF_8);
    }

    private record FileTaskActionResponse(
            boolean ok,
            FlashNotification notification,
            TaskPayload task,
            String redirectUrl
    ) {
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
