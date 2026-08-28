package io.github.fourilla.endervault.web.api.v1.file;

import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.task.FileOperationTaskService;
import io.github.fourilla.endervault.transfer.TransferBuffer;
import io.github.fourilla.endervault.transfer.TransferBufferItem;
import io.github.fourilla.endervault.transfer.TransferBufferService;
import io.github.fourilla.endervault.transfer.TransferOperation;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FileConflictPayload;
import io.github.fourilla.endervault.web.support.FileConflictPolicies;
import io.github.fourilla.endervault.web.support.FileConflictResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.SelectedItems;
import io.github.fourilla.endervault.web.task.TaskPayload;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/files/transfer-buffer")
public class TransferBufferApiController {

    private final StorageService storageService;
    private final TransferBufferService transferBufferService;
    private final FileOperationTaskService fileOperationTaskService;

    public TransferBufferApiController(
            StorageService storageService,
            TransferBufferService transferBufferService,
            FileOperationTaskService fileOperationTaskService
    ) {
        this.storageService = storageService;
        this.transferBufferService = transferBufferService;
        this.fileOperationTaskService = fileOperationTaskService;
    }

    @GetMapping
    public TransferBufferActionResponse current(HttpSession session) {
        return TransferBufferActionResponse.ok(null, transferBufferService.current(session));
    }

    @PostMapping
    public ResponseEntity<?> addSelected(
            @RequestParam(value = "path", required = false) String path,
            HttpSession session,
            HttpServletRequest request
    ) throws IOException {
        List<String> itemNames = SelectedItems.from(request);
        if (itemNames.isEmpty()) {
            return ResponseEntity.badRequest().body(ActionResponse.error("Select at least one item."));
        }

        List<FileItem> selectedItems = selectedItems(path, itemNames);
        TransferBuffer buffer = transferBufferService.add(session, selectedItems);
        FlashNotification notification = FlashNotification.success(
                "Added " + selectedItems.size() + " item(s) to transfer buffer."
        );
        return ResponseEntity.ok(TransferBufferActionResponse.ok(notification, buffer));
    }

    @PostMapping("/detail")
    public TransferBufferActionResponse addDetailItem(
            @RequestParam("path") String path,
            HttpSession session
    ) throws IOException {
        FileDetail detail = storageService.detail(StorageScope.VAULT, path);
        FileItem item = storageService.describeVaultChild(detail.parentPath(), detail.name());
        TransferBuffer buffer = transferBufferService.add(session, List.of(item));
        return TransferBufferActionResponse.ok(
                FlashNotification.success("Added item to transfer buffer."),
                buffer
        );
    }

    @PostMapping("/paste")
    public ResponseEntity<?> paste(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("operation") String operation,
            @RequestParam(value = "conflictPolicy", required = false) String conflictPolicy,
            HttpSession session,
            HttpServletRequest request
    ) throws IOException {
        TransferBuffer buffer = transferBufferService.current(session);
        if (!buffer.active()) {
            return ResponseEntity.badRequest().body(ActionResponse.error("Transfer buffer is empty."));
        }

        TransferOperation transferOperation = TransferOperation.from(operation);
        if (FileConflictPolicies.cancels(conflictPolicy, storageService.defaultConflictPolicy())) {
            return ResponseEntity.ok(TransferBufferActionResponse.ok(
                    FlashNotification.warning("Action canceled."),
                    buffer
            ));
        }

        if (FileConflictPolicies.asks(conflictPolicy)) {
            TransferBufferItem conflict = firstConflictingTarget(transferOperation, buffer.items(), path);
            if (conflict != null) {
                return conflictResponse(transferOperation, conflict, joinPath(path, conflict.name()));
            }
        }

        ConflictPolicy resolvedConflictPolicy = FileConflictPolicies.transferPolicy(
                conflictPolicy,
                storageService.defaultConflictPolicy()
        );
        AppTask task = fileOperationTaskService.queueTransfer(
                transferOperation,
                buffer.items(),
                path,
                request,
                resolvedConflictPolicy
        );
        transferBufferService.clear(session);
        FlashNotification notification = FlashNotification.info(transferOperation.label() + " task queued.");
        return ResponseEntity.accepted().body(TransferBufferActionResponse.ok(
                notification,
                TransferBuffer.empty(),
                TaskPayload.from(task)
        ));
    }

    @PostMapping("/clear")
    public TransferBufferActionResponse clear(HttpSession session) {
        transferBufferService.clear(session);
        return TransferBufferActionResponse.ok(
                FlashNotification.success("Transfer buffer cleared."),
                TransferBuffer.empty()
        );
    }

    @PostMapping("/remove")
    public TransferBufferActionResponse remove(
            @RequestParam("itemPath") String itemPath,
            HttpSession session
    ) {
        TransferBuffer buffer = transferBufferService.remove(session, itemPath);
        return TransferBufferActionResponse.ok(
                FlashNotification.success("Removed item from transfer buffer."),
                buffer
        );
    }

    private List<FileItem> selectedItems(String path, List<String> itemNames) throws IOException {
        List<FileItem> selectedItems = new ArrayList<>();
        for (String itemName : itemNames) {
            selectedItems.add(storageService.describeVaultChild(path, itemName));
        }
        return selectedItems;
    }

    private TransferBufferItem firstConflictingTarget(
            TransferOperation operation,
            List<TransferBufferItem> items,
            String targetDirectoryPath
    ) throws IOException {
        for (TransferBufferItem item : items) {
            String targetPath = joinPath(targetDirectoryPath, item.name());
            if (operation == TransferOperation.MOVE && item.path().equals(targetPath)) {
                continue;
            }
            try {
                storageService.describeVaultChild(targetDirectoryPath, item.name());
                return item;
            } catch (NoSuchFileException ignored) {
                // No existing target with the same name.
            }
        }
        return null;
    }

    private ResponseEntity<FileConflictResponse> conflictResponse(
            TransferOperation operation,
            TransferBufferItem item,
            String targetPath
    ) {
        String action = operation == TransferOperation.MOVE ? "move" : "copy";
        String message = "An item named \"" + item.name() + "\" already exists in the target directory.";
        return ResponseEntity.status(HttpStatus.CONFLICT).body(FileConflictResponse.conflict(
                new FileConflictPayload(
                        action,
                        item.name(),
                        targetPath,
                        storageService.defaultConflictPolicy().value(),
                        message
                ),
                null
        ));
    }

    private String joinPath(String directoryPath, String itemName) {
        if (directoryPath == null || directoryPath.isBlank()) {
            return itemName;
        }
        return directoryPath + "/" + itemName;
    }
}
