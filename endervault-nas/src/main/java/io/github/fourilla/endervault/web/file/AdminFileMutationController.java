package io.github.fourilla.endervault.web.file;

import static io.github.fourilla.endervault.web.file.FileRedirects.redirectToDetail;
import static io.github.fourilla.endervault.web.file.FileRedirects.redirectToFiles;
import static io.github.fourilla.endervault.web.file.FileRedirects.targetPath;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.FileLifecycleService;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.task.FileOperationTaskService;
import io.github.fourilla.endervault.trash.TrashRecord;
import io.github.fourilla.endervault.trash.TrashService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FileConflictPolicies;
import io.github.fourilla.endervault.web.support.FileConflictPayload;
import io.github.fourilla.endervault.web.support.FileConflictResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.SelectedItems;
import io.github.fourilla.endervault.web.task.TaskPayload;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminFileMutationController {

    private final StorageService storageService;
    private final FileLifecycleService fileLifecycleService;
    private final TrashService trashService;
    private final ActivityLogService activityLogService;
    private final FileOperationTaskService fileOperationTaskService;

    public AdminFileMutationController(
            StorageService storageService,
            FileLifecycleService fileLifecycleService,
            TrashService trashService,
            ActivityLogService activityLogService,
            FileOperationTaskService fileOperationTaskService
    ) {
        this.storageService = storageService;
        this.fileLifecycleService = fileLifecycleService;
        this.trashService = trashService;
        this.activityLogService = activityLogService;
        this.fileOperationTaskService = fileOperationTaskService;
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
        if (FileConflictPolicies.cancels(conflictPolicy, storageService.defaultConflictPolicy())) {
            return canceledMutation(request, redirectAttributes, redirectToFiles(path));
        }
        FileItem oldItem = storageService.describeVaultChild(path, item);
        String newPath;
        try {
            newPath = storageService.rename(path, item, newName, mutationPolicy(conflictPolicy));
        } catch (FileAlreadyExistsException ex) {
            if (FileConflictPolicies.asksForJson(conflictPolicy, request)) {
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
        if (FileConflictPolicies.cancels(conflictPolicy, storageService.defaultConflictPolicy())) {
            return canceledMutation(request, redirectAttributes, redirectToDetail(path));
        }
        FileDetail detail = detailForPath(path);
        String newPath;
        try {
            newPath = storageService.renameVaultPath(detail.path(), newName, mutationPolicy(conflictPolicy));
        } catch (FileAlreadyExistsException ex) {
            if (FileConflictPolicies.asksForJson(conflictPolicy, request)) {
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
        if (FileConflictPolicies.cancels(conflictPolicy, storageService.defaultConflictPolicy())) {
            return canceledMutation(request, redirectAttributes, redirectToFiles(path));
        }
        FileItem oldItem = storageService.describeVaultChild(path, item);
        String newPath;
        try {
            newPath = storageService.move(path, item, targetPath, mutationPolicy(conflictPolicy));
        } catch (FileAlreadyExistsException ex) {
            if (FileConflictPolicies.asksForJson(conflictPolicy, request)) {
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
        if (FileConflictPolicies.cancels(conflictPolicy, storageService.defaultConflictPolicy())) {
            return canceledMutation(request, redirectAttributes, redirectToDetail(path));
        }
        FileDetail detail = detailForPath(path);
        String newPath;
        try {
            newPath = storageService.moveVaultPath(detail.path(), targetPath, mutationPolicy(conflictPolicy));
        } catch (FileAlreadyExistsException ex) {
            if (FileConflictPolicies.asksForJson(conflictPolicy, request)) {
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

    private ConflictPolicy mutationPolicy(String conflictPolicy) {
        return FileConflictPolicies.mutationPolicy(conflictPolicy, storageService.defaultConflictPolicy());
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

    private record FileTaskActionResponse(
            boolean ok,
            FlashNotification notification,
            TaskPayload task,
            String redirectUrl
    ) {
    }

}
