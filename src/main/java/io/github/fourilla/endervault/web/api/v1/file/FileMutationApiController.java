package io.github.fourilla.endervault.web.api.v1.file;

import static io.github.fourilla.endervault.web.file.FileRedirects.detailUrl;
import static io.github.fourilla.endervault.web.file.FileRedirects.filesUrl;
import static io.github.fourilla.endervault.web.file.FileRedirects.targetPath;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.FileLifecycleService;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.task.FileOperationTaskService;
import io.github.fourilla.endervault.trash.TrashRecord;
import io.github.fourilla.endervault.trash.TrashService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FileConflictPayload;
import io.github.fourilla.endervault.web.support.FileConflictPolicies;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/files")
public class FileMutationApiController {

    private final StorageService storageService;
    private final FileLifecycleService fileLifecycleService;
    private final TrashService trashService;
    private final ActivityLogService activityLogService;
    private final FileOperationTaskService fileOperationTaskService;

    public FileMutationApiController(
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

    @PostMapping("/directories")
    public ActionResponse createDirectory(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("name") String name,
            HttpServletRequest request
    ) throws IOException {
        storageService.createDirectory(path, name);
        FileItem directory = storageService.describeVaultChild(path, name);
        activityLogService.record("CREATE_DIRECTORY", request, directory.path(), null, "Created directory " + name);
        return ActionResponse.redirect(FlashNotification.success("Directory created."), filesUrl(path));
    }

    @PostMapping
    public ActionResponse createFile(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("name") String name,
            HttpServletRequest request
    ) throws IOException {
        storageService.createFile(path, name);
        FileItem file = storageService.describeVaultChild(path, name);
        activityLogService.record("CREATE_FILE", request, file.path(), null, "Created file " + name);
        return ActionResponse.redirect(FlashNotification.success("File created."), filesUrl(path));
    }

    @PostMapping("/rename")
    public Object rename(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("item") String item,
            @RequestParam("newName") String newName,
            @RequestParam(value = "conflictPolicy", required = false) String conflictPolicy,
            HttpServletRequest request
    ) throws IOException {
        if (FileConflictPolicies.cancels(conflictPolicy, storageService.defaultConflictPolicy())) {
            return canceledMutation(filesUrl(path));
        }
        FileItem oldItem = storageService.describeVaultChild(path, item);
        String newPath;
        try {
            newPath = storageService.rename(path, item, newName, mutationPolicy(conflictPolicy));
        } catch (FileAlreadyExistsException ex) {
            if (FileConflictPolicies.asks(conflictPolicy)) {
                return conflictResponse(
                        "rename",
                        oldItem.name(),
                        targetPath(path, newName),
                        "An item named \"" + newName + "\" already exists.",
                        filesUrl(path)
                );
            }
            throw ex;
        }
        FileItem newItem = storageService.describeVaultPath(newPath);
        fileLifecycleService.recordRename(request, oldItem.path(), newItem.path(), "Renamed item to " + newItem.name());
        return ActionResponse.redirect(FlashNotification.success("Item renamed."), filesUrl(path));
    }

    @PostMapping("/detail/rename")
    public Object renameFromDetail(
            @RequestParam("path") String path,
            @RequestParam("newName") String newName,
            @RequestParam(value = "conflictPolicy", required = false) String conflictPolicy,
            HttpServletRequest request
    ) throws IOException {
        if (FileConflictPolicies.cancels(conflictPolicy, storageService.defaultConflictPolicy())) {
            return canceledMutation(detailUrl(path));
        }
        FileDetail detail = detailForPath(path);
        String newPath;
        try {
            newPath = storageService.renameVaultPath(detail.path(), newName, mutationPolicy(conflictPolicy));
        } catch (FileAlreadyExistsException ex) {
            if (FileConflictPolicies.asks(conflictPolicy)) {
                return conflictResponse(
                        "rename",
                        detail.name(),
                        targetPath(detail.parentPath(), newName),
                        "An item named \"" + newName + "\" already exists.",
                        detailUrl(path)
                );
            }
            throw ex;
        }
        fileLifecycleService.recordRename(request, detail.path(), newPath, "Renamed item to " + newName);
        return ActionResponse.redirect(FlashNotification.success("Item renamed."), detailUrl(newPath));
    }

    @PostMapping("/detail/hidden")
    public Object setHiddenFromDetail(
            @RequestParam("path") String path,
            @RequestParam("hidden") boolean hidden,
            @RequestParam(value = "conflictPolicy", required = false) String conflictPolicy,
            HttpServletRequest request
    ) throws IOException {
        if (FileConflictPolicies.cancels(conflictPolicy, storageService.defaultConflictPolicy())) {
            return canceledMutation(detailUrl(path));
        }
        FileDetail detail = detailForPath(path);
        String newPath;
        try {
            newPath = storageService.setHiddenVaultPath(detail.path(), hidden, mutationPolicy(conflictPolicy));
        } catch (FileAlreadyExistsException ex) {
            if (FileConflictPolicies.asks(conflictPolicy)) {
                String targetName = hidden ? "." + detail.name() : detail.name().replaceFirst("^\\.+", "");
                return conflictResponse(
                        hidden ? "hide" : "unhide",
                        detail.name(),
                        targetPath(detail.parentPath(), targetName),
                        "An item named \"" + targetName + "\" already exists.",
                        detailUrl(path)
                );
            }
            throw ex;
        }

        fileLifecycleService.recordHiddenChange(
                request,
                detail.path(),
                newPath,
                hidden,
                hidden ? "Marked item as hidden" : "Marked item as visible"
        );
        return ActionResponse.redirect(
                FlashNotification.success(hidden ? "Item hidden." : "Item visible."),
                detailUrl(newPath)
        );
    }

    @PostMapping("/trash")
    public ResponseEntity<?> delete(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request
    ) throws IOException {
        List<String> items = SelectedItems.from(request);
        String redirectUrl = filesUrl(path, view, sort, direction, page, size);
        if (items.isEmpty()) {
            return ResponseEntity.badRequest().body(ActionResponse.error("Select at least one item."));
        }

        AppTask task = fileOperationTaskService.queueMoveToTrash(path, items, request);
        FlashNotification notification = FlashNotification.info("Trash task queued.");
        return ResponseEntity.accepted().body(new FileTaskActionResponse(
                true,
                notification,
                TaskPayload.from(task),
                redirectUrl
        ));
    }

    @PostMapping("/detail/trash")
    public ActionResponse deleteFromDetail(
            @RequestParam("path") String path,
            HttpServletRequest request
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        TrashRecord record = trashService.moveVaultPathToTrash(detail.path());
        activityLogService.record("TRASH_MOVE", request, record.originalPath(), null, "Moved item to trash");
        return ActionResponse.redirect(
                FlashNotification.success("Item moved to trash."),
                filesUrl(detail.parentPath())
        );
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

    private ActionResponse canceledMutation(String redirectUrl) {
        return ActionResponse.redirect(FlashNotification.warning("Action canceled."), redirectUrl);
    }

    private ResponseEntity<FileConflictResponse> conflictResponse(
            String operation,
            String itemName,
            String targetPath,
            String message,
            String redirectUrl
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(FileConflictResponse.conflict(
                new FileConflictPayload(
                        operation,
                        itemName,
                        targetPath,
                        storageService.defaultConflictPolicy().value(),
                        message
                ),
                redirectUrl
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
