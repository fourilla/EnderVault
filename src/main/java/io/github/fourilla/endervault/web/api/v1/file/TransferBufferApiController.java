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
import io.github.fourilla.endervault.web.support.VaultSelectionResolver;
import io.github.fourilla.endervault.web.task.TaskPayload;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
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
    private final VaultSelectionResolver vaultSelectionResolver;
    private final TransferBufferService transferBufferService;
    private final FileOperationTaskService fileOperationTaskService;

    public TransferBufferApiController(
            StorageService storageService,
            VaultSelectionResolver vaultSelectionResolver,
            TransferBufferService transferBufferService,
            FileOperationTaskService fileOperationTaskService
    ) {
        this.storageService = storageService;
        this.vaultSelectionResolver = vaultSelectionResolver;
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
        List<FileItem> selectedItems = vaultSelectionResolver.resolve(request, path);
        if (selectedItems.isEmpty()) {
            return ResponseEntity.badRequest().body(ActionResponse.error("Select at least one item."));
        }
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
            @RequestParam(defaultValue = "false") boolean directoryTransferConfirmed,
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

        if (!directoryTransferConfirmed) {
            List<String> conflicts = conflictingDirectories(buffer.items(), path);
            if (!conflicts.isEmpty()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(java.util.Map.of(
                        "directoryTransferConfirmation", conflicts,
                        "transferBuffer", TransferBufferActionResponse.TransferBufferPayload.from(buffer)));
            }
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

    private TransferBufferItem firstConflictingTarget(
            TransferOperation operation,
            List<TransferBufferItem> items,
            String targetDirectoryPath
    ) throws IOException {
        String destination = storageService.normalizeVaultDirectory(targetDirectoryPath);
        for (TransferBufferItem item : items) {
            try {
                var source = storageService.describeVaultPath(item.path());
                if (source.directory() && !source.parentPath().equals(destination)) {
                    // Directory conflicts are persisted and reviewed by the background merge workflow.
                    continue;
                }
            } catch (NoSuchFileException ignored) {
                // Let the worker report stale sources without blocking the rest of the batch.
                continue;
            }
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

    private List<String> conflictingDirectories(List<TransferBufferItem> items, String path) throws IOException {
        String destination = storageService.normalizeVaultDirectory(path);
        var conflicts = new java.util.ArrayList<String>();
        for (var item : items) {
            FileItem source;
            try { source = storageService.describeVaultPath(item.path()); }
            catch (NoSuchFileException ignored) { continue; }
            if (!source.directory() || source.parentPath().equals(destination)) continue;
            try {
                storageService.describeVaultChild(destination, item.name());
                conflicts.add(joinPath(destination, item.name()));
            } catch (NoSuchFileException ignored) {
                // No top-level collision; the worker still checks again before publication.
            }
        }
        return List.copyOf(conflicts);
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
