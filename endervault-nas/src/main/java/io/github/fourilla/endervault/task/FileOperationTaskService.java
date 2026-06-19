package io.github.fourilla.endervault.task;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.auth.ClientIpResolver;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.recent.RecentService;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.storage.StorageOperationSummary;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.thumbnail.ThumbnailService;
import io.github.fourilla.endervault.trash.TrashRecord;
import io.github.fourilla.endervault.trash.TrashService;
import io.github.fourilla.endervault.transfer.TransferBufferItem;
import io.github.fourilla.endervault.transfer.TransferOperation;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class FileOperationTaskService {

    private final TaskManagerService taskManagerService;
    private final StorageService storageService;
    private final ShareLinkService shareLinkService;
    private final FavoriteService favoriteService;
    private final RecentService recentService;
    private final ThumbnailService thumbnailService;
    private final TrashService trashService;
    private final ActivityLogService activityLogService;
    private final ClientIpResolver clientIpResolver;

    public FileOperationTaskService(
            TaskManagerService taskManagerService,
            StorageService storageService,
            ShareLinkService shareLinkService,
            FavoriteService favoriteService,
            RecentService recentService,
            ThumbnailService thumbnailService,
            TrashService trashService,
            ActivityLogService activityLogService,
            ClientIpResolver clientIpResolver
    ) {
        this.taskManagerService = taskManagerService;
        this.storageService = storageService;
        this.shareLinkService = shareLinkService;
        this.favoriteService = favoriteService;
        this.recentService = recentService;
        this.thumbnailService = thumbnailService;
        this.trashService = trashService;
        this.activityLogService = activityLogService;
        this.clientIpResolver = clientIpResolver;
    }

    public AppTask queueTransfer(
            TransferOperation operation,
            List<TransferBufferItem> items,
            String targetPath,
            HttpServletRequest request
    ) {
        List<TransferBufferItem> snapshot = List.copyOf(items);
        RequestSnapshot requestSnapshot = RequestSnapshot.from(request, clientIpResolver);
        TaskType type = operation == TransferOperation.MOVE ? TaskType.FILE_MOVE : TaskType.FILE_COPY;
        String title = operation.label() + " " + snapshot.size() + " item(s)";
        return taskManagerService.submit(
                type,
                title,
                targetPath,
                requestSnapshot.actor(),
                requestSnapshot.ip(),
                context -> runTransfer(operation, snapshot, targetPath, requestSnapshot, context)
        );
    }

    public AppTask queueMoveToTrash(
            String directoryPath,
            List<String> itemNames,
            HttpServletRequest request
    ) {
        List<String> snapshot = List.copyOf(itemNames);
        RequestSnapshot requestSnapshot = RequestSnapshot.from(request, clientIpResolver);
        return taskManagerService.submit(
                TaskType.FILE_TRASH,
                "Move " + snapshot.size() + " item(s) to trash",
                directoryPath,
                requestSnapshot.actor(),
                requestSnapshot.ip(),
                context -> runMoveToTrash(directoryPath, snapshot, requestSnapshot, context)
        );
    }

    private TaskOutcome runTransfer(
            TransferOperation operation,
            List<TransferBufferItem> items,
            String targetPath,
            RequestSnapshot request,
            TaskContext context
    ) throws IOException {
        context.message("Preparing " + operation.label().toLowerCase(Locale.ROOT) + ".");
        if (operation == TransferOperation.COPY) {
            StorageOperationSummary summary = storageService.summarizeVaultPaths(
                    items.stream().map(TransferBufferItem::path).toList()
            );
            context.setTotalBytes(summary.totalBytes());
            context.setTotalItems(summary.totalItems());
        } else {
            context.setTotalItems(items.size());
        }

        int completedCount = 0;
        int failedCount = 0;
        for (TransferBufferItem item : items) {
            context.checkCanceled();
            context.message(runningLabel(operation) + " " + item.name());
            try {
                String newPath = operation == TransferOperation.MOVE
                        ? storageService.moveVaultPath(item.path(), targetPath)
                        : storageService.copyVaultPath(item.path(), targetPath, listener(context));
                if (operation == TransferOperation.MOVE) {
                    context.incrementProcessedItems();
                    finishMovedItem(item, newPath, request);
                } else {
                    finishCopiedItem(item, newPath, targetPath, request);
                }
                completedCount++;
            } catch (TaskCanceledException ex) {
                throw ex;
            } catch (StorageAccessException | IOException ex) {
                failedCount++;
                recordFailedTransfer(operation, item, targetPath, request, ex);
            }
        }

        String message = transferMessage(operation, completedCount, failedCount);
        return failedCount == 0 ? TaskOutcome.complete(message) : TaskOutcome.partial(message);
    }

    private TaskOutcome runMoveToTrash(
            String directoryPath,
            List<String> itemNames,
            RequestSnapshot request,
            TaskContext context
    ) throws IOException {
        context.setTotalItems(itemNames.size());
        int completedCount = 0;
        int failedCount = 0;
        for (String itemName : itemNames) {
            context.checkCanceled();
            context.message("Moving " + itemName + " to trash.");
            try {
                List<TrashRecord> records = trashService.moveToTrash(directoryPath, List.of(itemName));
                for (TrashRecord record : records) {
                    activityLogService.record(
                            "TRASH_MOVE",
                            request.actor(),
                            request.ip(),
                            record.originalPath(),
                            null,
                            true,
                            "Moved item to trash",
                            Map.of()
                    );
                }
                completedCount += records.size();
                context.incrementProcessedItems();
            } catch (TaskCanceledException ex) {
                throw ex;
            } catch (StorageAccessException | IOException ex) {
                failedCount++;
                activityLogService.record(
                        "TRASH_MOVE",
                        request.actor(),
                        request.ip(),
                        itemName,
                        null,
                        false,
                        "Could not move item to trash",
                        Map.of("reason", ex.getClass().getSimpleName())
                );
                context.incrementProcessedItems();
            }
        }

        String message = failedCount == 0
                ? "Moved " + completedCount + " item(s) to trash."
                : "Moved " + completedCount + " item(s) to trash. " + failedCount + " failed.";
        return failedCount == 0 ? TaskOutcome.complete(message) : TaskOutcome.partial(message);
    }

    private StorageProgressListener listener(TaskContext context) {
        return new StorageProgressListener() {
            @Override
            public void checkCanceled() {
                context.checkCanceled();
            }

            @Override
            public void onBytesProcessed(long bytes) {
                context.addProcessedBytes(bytes);
            }

            @Override
            public void onItemProcessed() {
                context.incrementProcessedItems();
            }
        };
    }

    private void finishMovedItem(TransferBufferItem item, String newPath, RequestSnapshot request)
            throws IOException {
        thumbnailService.migrateThumbnails(storageService.resolveVaultPath(newPath), item.path(), newPath);
        shareLinkService.moveVaultPath(item.path(), newPath);
        favoriteService.moveVaultPath(item.path(), newPath);
        recentService.moveVaultPath(item.path(), newPath);
        activityLogService.record("MOVE", request.actor(), request.ip(), item.path(), newPath, true, "Moved item", Map.of());
    }

    private void finishCopiedItem(TransferBufferItem item, String newPath, String targetPath, RequestSnapshot request) {
        activityLogService.record(
                "COPY",
                request.actor(),
                request.ip(),
                item.path(),
                newPath,
                true,
                "Copied item to " + targetPath,
                Map.of()
        );
    }

    private void recordFailedTransfer(
            TransferOperation operation,
            TransferBufferItem item,
            String targetPath,
            RequestSnapshot request,
            Exception failure
    ) {
        activityLogService.record(
                operation.activityType(),
                request.actor(),
                request.ip(),
                item.path(),
                targetPath,
                false,
                "Could not " + operation.label().toLowerCase(Locale.ROOT) + " item",
                Map.of("reason", failure.getClass().getSimpleName())
        );
    }

    private String transferMessage(TransferOperation operation, int completedCount, int failedCount) {
        if (failedCount == 0) {
            return operation.completedLabel() + " " + completedCount + " item(s).";
        }
        return operation.completedLabel() + " " + completedCount + " item(s). "
                + failedCount + " item(s) failed.";
    }

    private String runningLabel(TransferOperation operation) {
        return operation == TransferOperation.MOVE ? "Moving" : "Copying";
    }

    private record RequestSnapshot(
            String actor,
            String ip
    ) {
        static RequestSnapshot from(HttpServletRequest request, ClientIpResolver clientIpResolver) {
            Principal principal = request == null ? null : request.getUserPrincipal();
            String actor = principal == null ? "anonymous" : principal.getName();
            String ip = request == null ? "-" : clientIpResolver.resolve(request);
            return new RequestSnapshot(actor, ip);
        }
    }
}
