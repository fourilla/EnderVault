package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.recent.RecentService;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.thumbnail.ThumbnailService;
import io.github.fourilla.endervault.transfer.TransferBuffer;
import io.github.fourilla.endervault.transfer.TransferBufferItem;
import io.github.fourilla.endervault.transfer.TransferBufferService;
import io.github.fourilla.endervault.transfer.TransferOperation;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.SelectedItems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
public class AdminFileTransferBufferController {

    private final StorageService storageService;
    private final ShareLinkService shareLinkService;
    private final FavoriteService favoriteService;
    private final RecentService recentService;
    private final ThumbnailService thumbnailService;
    private final ActivityLogService activityLogService;
    private final TransferBufferService transferBufferService;

    public AdminFileTransferBufferController(
            StorageService storageService,
            ShareLinkService shareLinkService,
            FavoriteService favoriteService,
            RecentService recentService,
            ThumbnailService thumbnailService,
            ActivityLogService activityLogService,
            TransferBufferService transferBufferService
    ) {
        this.storageService = storageService;
        this.shareLinkService = shareLinkService;
        this.favoriteService = favoriteService;
        this.recentService = recentService;
        this.thumbnailService = thumbnailService;
        this.activityLogService = activityLogService;
        this.transferBufferService = transferBufferService;
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

    @PostMapping("/files/transfer/paste")
    public Object paste(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("operation") String operation,
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
        List<TransferBufferItem> failedItems = new ArrayList<>();
        int completedCount = 0;
        for (TransferBufferItem item : buffer.items()) {
            TransferAttempt attempt = attemptTransfer(transferOperation, item, path);
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
                redirectToFiles(path, null),
                TransferBufferActionResponse.ok(notification, TransferBufferPayload.from(TransferBuffer.empty()))
        );
    }

    @PostMapping("/files/transfer/remove")
    public Object remove(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("itemPath") String itemPath,
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
                redirectToFiles(path, null),
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

    private TransferAttempt attemptTransfer(TransferOperation operation, TransferBufferItem item, String targetPath)
            throws IOException {
        try {
            String newPath = operation == TransferOperation.MOVE
                    ? storageService.moveVaultPath(item.path(), targetPath)
                    : storageService.copyVaultPath(item.path(), targetPath);
            return TransferAttempt.success(newPath);
        } catch (StorageAccessException ex) {
            return TransferAttempt.failure(ex);
        } catch (IOException ex) {
            return TransferAttempt.failure(ex);
        }
    }

    private void finishMovedItem(TransferBufferItem item, String newPath, HttpServletRequest request)
            throws IOException {
        thumbnailService.migrateThumbnails(storageService.resolveVaultPath(newPath), item.path(), newPath);
        shareLinkService.moveVaultPath(item.path(), newPath);
        favoriteService.moveVaultPath(item.path(), newPath);
        recentService.moveVaultPath(item.path(), newPath);
        activityLogService.record("MOVE", request, item.path(), newPath, "Moved item");
    }

    private void finishCopiedItem(TransferBufferItem item, String newPath, String targetPath, HttpServletRequest request) {
        activityLogService.record("COPY", request, item.path(), newPath, "Copied item to " + targetPath);
    }

    private void recordFailedTransfer(
            TransferOperation operation,
            TransferBufferItem item,
            String targetPath,
            HttpServletRequest request,
            Exception failure
    ) {
        activityLogService.record(
                operation.activityType(),
                request,
                item.path(),
                targetPath,
                false,
                "Could not " + operation.label().toLowerCase(Locale.ROOT) + " item",
                Map.of("reason", failure.getClass().getSimpleName())
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

    private record TransferBufferActionResponse(
            boolean ok,
            FlashNotification notification,
            TransferBufferPayload transferBuffer
    ) {
        static TransferBufferActionResponse ok(FlashNotification notification, TransferBufferPayload transferBuffer) {
            return new TransferBufferActionResponse(true, notification, transferBuffer);
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
