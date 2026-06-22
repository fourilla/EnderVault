package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.FileLifecycleService;
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
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FileConflictPayload;
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
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
public class AdminFileTransferBufferController {

    private final StorageService storageService;
    private final FileLifecycleService fileLifecycleService;
    private final TransferBufferService transferBufferService;
    private final FileOperationTaskService fileOperationTaskService;

    public AdminFileTransferBufferController(
            StorageService storageService,
            FileLifecycleService fileLifecycleService,
            TransferBufferService transferBufferService,
            FileOperationTaskService fileOperationTaskService
    ) {
        this.storageService = storageService;
        this.fileLifecycleService = fileLifecycleService;
        this.transferBufferService = transferBufferService;
        this.fileOperationTaskService = fileOperationTaskService;
    }

    @PostMapping("/files/transfer/buffer")
    public Object bufferSelected(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "page", required = false) Integer page,
            HttpSession session,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        List<String> itemNames = SelectedItems.from(request);
        if (itemNames.isEmpty()) {
            return ActionResponseSupport.badRequest(
                    request,
                    redirectAttributes,
                    FlashNotification.warning("Select at least one item."),
                    redirectToFiles(path, page)
            );
        }

        List<FileItem> selectedItems = selectedItems(path, itemNames);
        TransferBuffer buffer = transferBufferService.add(session, selectedItems);

        FlashNotification notification = FlashNotification.success(
                "Added " + selectedItems.size() + " item(s) to transfer buffer."
        );
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                redirectToFiles(path, page),
                TransferBufferActionResponse.ok(notification, TransferBufferPayload.from(buffer))
        );
    }

    @PostMapping("/files/detail/transfer/buffer")
    public Object bufferDetailItem(
            @RequestParam("path") String path,
            HttpSession session,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileDetail detail = storageService.detail(StorageScope.VAULT, path);
        FileItem item = storageService.describeVaultChild(detail.parentPath(), detail.name());
        TransferBuffer buffer = transferBufferService.add(session, List.of(item));

        FlashNotification notification = FlashNotification.success("Added item to transfer buffer.");
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                redirectToDetail(detail.path()),
                TransferBufferActionResponse.ok(notification, TransferBufferPayload.from(buffer))
        );
    }

    @PostMapping("/files/transfer/paste")
    public Object paste(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("operation") String operation,
            @RequestParam(value = "conflictPolicy", required = false) String conflictPolicy,
            HttpSession session,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        TransferBuffer buffer = transferBufferService.current(session);
        if (!buffer.active()) {
            return ActionResponseSupport.badRequest(
                    request,
                    redirectAttributes,
                    FlashNotification.warning("Transfer buffer is empty."),
                    redirectToFiles(path, null)
            );
        }

        TransferOperation transferOperation = TransferOperation.from(operation);
        if (isCancelConflictPolicy(conflictPolicy)) {
            FlashNotification notification = FlashNotification.warning("Action canceled.");
            return ActionResponseSupport.ok(
                    request,
                    redirectAttributes,
                    notification,
                    redirectToFiles(path, null),
                    TransferBufferActionResponse.ok(notification, TransferBufferPayload.from(buffer))
            );
        }

        if (asksConflictPolicy(conflictPolicy, request)) {
            TransferBufferItem conflict = firstConflictingTarget(transferOperation, buffer.items(), path);
            if (conflict != null) {
                return conflictResponse(
                        transferOperation,
                        conflict,
                        joinPath(path, conflict.name()),
                        redirectToFiles(path, null)
                );
            }
        }

        ConflictPolicy resolvedConflictPolicy = transferConflictPolicy(conflictPolicy);
        if (ActionResponseSupport.wantsJson(request)) {
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
                    TransferBufferPayload.from(TransferBuffer.empty()),
                    TaskPayload.from(task)
            ));
        }

        List<TransferBufferItem> failedItems = new ArrayList<>();
        int completedCount = 0;
        for (TransferBufferItem item : buffer.items()) {
            TransferAttempt attempt = attemptTransfer(transferOperation, item, path, resolvedConflictPolicy);
            if (!attempt.success()) {
                failedItems.add(item);
                recordFailedTransfer(transferOperation, item, path, request, attempt.failure());
                continue;
            }

            if (transferOperation == TransferOperation.MOVE) {
                finishMovedItem(item, attempt.newPath(), request);
            } else {
                finishCopiedItem(item, attempt.newPath(), path, request);
            }
            completedCount++;
        }

        transferBufferService.replace(session, failedItems);

        FlashNotification notification = transferNotification(transferOperation, completedCount, failedItems.size());
        return ActionResponseSupport.redirect(request, redirectAttributes, notification, redirectToFiles(path, null));
    }

    @PostMapping("/files/transfer/clear")
    public Object clear(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "returnTo", required = false) String returnTo,
            HttpSession session,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        transferBufferService.clear(session);
        FlashNotification notification = FlashNotification.success("Transfer buffer cleared.");
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                redirectToReturn(path, returnTo),
                TransferBufferActionResponse.ok(notification, TransferBufferPayload.from(TransferBuffer.empty()))
        );
    }

    @PostMapping("/files/transfer/remove")
    public Object remove(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("itemPath") String itemPath,
            @RequestParam(value = "returnTo", required = false) String returnTo,
            HttpSession session,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        TransferBuffer buffer = transferBufferService.remove(session, itemPath);
        FlashNotification notification = FlashNotification.success("Removed item from transfer buffer.");
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                redirectToReturn(path, returnTo),
                TransferBufferActionResponse.ok(notification, TransferBufferPayload.from(buffer))
        );
    }

    private List<FileItem> selectedItems(String path, List<String> itemNames) throws IOException {
        List<FileItem> selectedItems = new ArrayList<>();
        for (String itemName : itemNames) {
            selectedItems.add(storageService.describeVaultChild(path, itemName));
        }
        return selectedItems;
    }

    private TransferAttempt attemptTransfer(
            TransferOperation operation,
            TransferBufferItem item,
            String targetPath,
            ConflictPolicy conflictPolicy
    )
            throws IOException {
        try {
            String newPath = operation == TransferOperation.MOVE
                    ? storageService.moveVaultPath(item.path(), targetPath, conflictPolicy)
                    : storageService.copyVaultPath(item.path(), targetPath, conflictPolicy);
            return TransferAttempt.success(newPath);
        } catch (StorageAccessException ex) {
            return TransferAttempt.failure(ex);
        } catch (IOException ex) {
            return TransferAttempt.failure(ex);
        }
    }

    private void finishMovedItem(TransferBufferItem item, String newPath, HttpServletRequest request)
            throws IOException {
        fileLifecycleService.recordMove(request, item.path(), newPath, "Moved item");
    }

    private void finishCopiedItem(TransferBufferItem item, String newPath, String targetPath, HttpServletRequest request) {
        fileLifecycleService.recordCopy(request, item.path(), newPath, "Copied item to " + targetPath);
    }

    private void recordFailedTransfer(
            TransferOperation operation,
            TransferBufferItem item,
            String targetPath,
            HttpServletRequest request,
            Exception failure
    ) {
        fileLifecycleService.recordFailedTransfer(
                operation.activityType(),
                request,
                item.path(),
                targetPath,
                "Could not " + operation.label().toLowerCase(Locale.ROOT) + " item",
                failure
        );
    }

    private FlashNotification transferNotification(TransferOperation operation, int completedCount, int failedCount) {
        String action = operation.completedLabel().toLowerCase(Locale.ROOT);
        if (failedCount == 0) {
            return FlashNotification.success(operation.completedLabel() + " " + completedCount + " item(s).");
        }
        if (completedCount == 0) {
            return FlashNotification.warning(
                    "No items were " + action + ". " + failedCount + " item(s) stayed in the buffer."
            );
        }
        return FlashNotification.warning(
                operation.completedLabel() + " " + completedCount + " item(s). "
                        + failedCount + " item(s) could not be " + action + " and stayed in the buffer."
        );
    }

    private TransferBufferItem firstConflictingTarget(
            TransferOperation operation,
            List<TransferBufferItem> items,
            String targetDirectoryPath
    )
            throws IOException {
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
            String targetPath,
            String redirect
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
                ActionResponseSupport.redirectUrl(redirect)
        ));
    }

    private ConflictPolicy transferConflictPolicy(String conflictPolicy) {
        if (asksConflictPolicy(conflictPolicy) || "default".equalsIgnoreCase(clean(conflictPolicy))) {
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

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String joinPath(String directoryPath, String itemName) {
        if (directoryPath == null || directoryPath.isBlank()) {
            return itemName;
        }
        return directoryPath + "/" + itemName;
    }

    private String redirectToFiles(String path, Integer page) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/files");
        if (path != null && !path.isBlank()) {
            builder.queryParam("path", path);
        }
        if (page != null && page > 1) {
            builder.queryParam("page", page);
        }
        return "redirect:" + builder.build().encode().toUriString();
    }

    private String redirectToDetail(String path) {
        return "redirect:" + UriComponentsBuilder.fromPath("/files/detail")
                .queryParam("path", path)
                .build()
                .encode()
                .toUriString();
    }

    private String redirectToReturn(String path, String returnTo) {
        if (safeReturnTo(returnTo)) {
            return "redirect:" + returnTo;
        }
        return redirectToFiles(path, null);
    }

    private boolean safeReturnTo(String returnTo) {
        return returnTo != null
                && returnTo.startsWith("/files")
                && !returnTo.startsWith("//")
                && !returnTo.contains("\r")
                && !returnTo.contains("\n");
    }

    private record TransferBufferActionResponse(
            boolean ok,
            FlashNotification notification,
            TransferBufferPayload transferBuffer,
            TaskPayload task
    ) {
        static TransferBufferActionResponse ok(FlashNotification notification, TransferBufferPayload transferBuffer) {
            return ok(notification, transferBuffer, null);
        }

        static TransferBufferActionResponse ok(
                FlashNotification notification,
                TransferBufferPayload transferBuffer,
                TaskPayload task
        ) {
            return new TransferBufferActionResponse(true, notification, transferBuffer, task);
        }
    }

    private record TransferBufferPayload(
            boolean active,
            int count,
            List<TransferBufferItemPayload> items
    ) {
        static TransferBufferPayload from(TransferBuffer buffer) {
            return new TransferBufferPayload(
                    buffer.active(),
                    buffer.count(),
                    buffer.items().stream()
                            .map(TransferBufferItemPayload::from)
                            .toList()
            );
        }
    }

    private record TransferBufferItemPayload(
            String path,
            String name,
            String iconClass
    ) {
        static TransferBufferItemPayload from(TransferBufferItem item) {
            return new TransferBufferItemPayload(item.path(), item.name(), item.iconClass());
        }
    }

    private record TransferAttempt(
            boolean success,
            String newPath,
            Exception failure
    ) {
        static TransferAttempt success(String newPath) {
            return new TransferAttempt(true, newPath, null);
        }

        static TransferAttempt failure(Exception failure) {
            return new TransferAttempt(false, null, failure);
        }
    }
}
