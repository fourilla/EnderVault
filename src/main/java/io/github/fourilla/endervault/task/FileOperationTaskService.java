package io.github.fourilla.endervault.task;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.auth.ClientIpResolver;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.directorytransfer.DirectoryTransferPlan;
import io.github.fourilla.endervault.directorytransfer.DirectoryTransferPlanner;
import io.github.fourilla.endervault.directorytransfer.DirectoryTransferReviewStore;
import io.github.fourilla.endervault.directorytransfer.DirectoryTransferRun;
import io.github.fourilla.endervault.directorytransfer.DirectoryTransferService;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.FileLifecycleService;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.storage.StorageOperationSummary;
import io.github.fourilla.endervault.storage.StorageService;
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
    private final FileLifecycleService fileLifecycleService;
    private final TrashService trashService;
    private final ActivityLogService activityLogService;
    private final ClientIpResolver clientIpResolver;
    private final DirectoryTransferPlanner transferPlanner;
    private final DirectoryTransferReviewStore transferReviews;
    private final DirectoryTransferService directoryTransfers;

    public FileOperationTaskService(
            TaskManagerService taskManagerService,
            StorageService storageService,
            FileLifecycleService fileLifecycleService,
            TrashService trashService,
            ActivityLogService activityLogService,
            ClientIpResolver clientIpResolver,
            DirectoryTransferPlanner transferPlanner,
            DirectoryTransferReviewStore transferReviews,
            DirectoryTransferService directoryTransfers
    ) {
        this.taskManagerService = taskManagerService;
        this.storageService = storageService;
        this.fileLifecycleService = fileLifecycleService;
        this.trashService = trashService;
        this.activityLogService = activityLogService;
        this.clientIpResolver = clientIpResolver;
        this.transferPlanner = transferPlanner;
        this.transferReviews = transferReviews;
        this.directoryTransfers = directoryTransfers;
    }

    public AppTask queueTransfer(
            TransferOperation operation,
            List<TransferBufferItem> items,
            String targetPath,
            HttpServletRequest request
    ) {
        return queueTransfer(operation, items, targetPath, request, null);
    }

    public AppTask queueTransfer(
            TransferOperation operation,
            List<TransferBufferItem> items,
            String targetPath,
            HttpServletRequest request,
            ConflictPolicy conflictPolicy
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
                context -> runTransfer(operation, snapshot, targetPath, requestSnapshot, context, conflictPolicy)
        );
    }

    public AppTask queueMoveToTrash(
            String contextPath,
            List<FileItem> items,
            HttpServletRequest request
    ) {
        List<FileItem> snapshot = List.copyOf(items);
        RequestSnapshot requestSnapshot = RequestSnapshot.from(request, clientIpResolver);
        return taskManagerService.submit(
                TaskType.FILE_TRASH,
                "Move " + snapshot.size() + " item(s) to trash",
                contextPath,
                requestSnapshot.actor(),
                requestSnapshot.ip(),
                context -> runMoveToTrash(snapshot, requestSnapshot, context)
        );
    }

    private TaskOutcome runTransfer(
            TransferOperation operation,
            List<TransferBufferItem> items,
            String targetPath,
            RequestSnapshot request,
            TaskContext context,
            ConflictPolicy conflictPolicy
    ) throws IOException {
        context.message("Preparing " + operation.label().toLowerCase(Locale.ROOT) + ".");
        String destination = storageService.normalizeVaultDirectory(targetPath);
        if (items.stream().noneMatch(TransferBufferItem::directory)) {
            if (operation == TransferOperation.COPY) {
                StorageOperationSummary summary = storageService.summarizeVaultPaths(
                        items.stream().map(TransferBufferItem::path).toList());
                context.setTotalBytes(summary.totalBytes());
                context.setTotalItems(summary.totalItems());
            } else {
                context.setTotalItems(items.size());
            }
        }

        int completedCount = 0;
        int failedCount = 0;
        int pendingCount = 0;
        String firstReview = null;
        for (TransferBufferItem item : items) {
            context.checkCanceled();
            context.message(runningLabel(operation) + " " + item.name());
            String savedReview = null;
            try {
                FileItem source = storageService.describeVaultPath(item.path());
                if (source.directory() && !source.parentPath().equals(destination)) {
                    var plan = transferPlanner.planTransfer(operation == TransferOperation.MOVE
                            ? DirectoryTransferPlan.Operation.MOVE : DirectoryTransferPlan.Operation.COPY,
                            source.path(), destination, cancellationListener(context));
                    var review = transferReviews.create(plan);
                    savedReview = plan.id();
                    context.directoryTransferReview(plan.id());
                    context.resultReference(plan.id());
                    boolean complete = review.fullyReviewed()
                            && directoryTransfers.execute(plan.id(), review.revision(), cancellationListener(context))
                                    .phase() == DirectoryTransferRun.Phase.COMPLETE;
                    if (complete) {
                        // The merge finalizer already updates moved-path metadata item by item.
                        completedCount++;
                        activityLogService.record(operation.activityType(), request.actor(), request.ip(),
                                source.path(), plan.destinationPath(), true, "Completed directory transfer", Map.of());
                    } else {
                        pendingCount++;
                        if (firstReview == null) firstReview = plan.id();
                    }
                    continue;
                }
                if (source.directory() && operation == TransferOperation.MOVE) {
                    completedCount++;
                    continue;
                }
                if (source.directory() && conflictPolicy != ConflictPolicy.RENAME) {
                    throw new StorageAccessException("Copying a directory to its current location requires Keep both.");
                }
                String newPath = operation == TransferOperation.MOVE
                        ? storageService.moveVaultPath(item.path(), targetPath, conflictPolicy)
                        : storageService.copyVaultPath(item.path(), targetPath, listener(context), conflictPolicy);
                if (operation == TransferOperation.MOVE) {
                    context.incrementProcessedItems();
                    finishMovedItem(item, newPath, request);
                } else {
                    finishCopiedItem(item, newPath, targetPath, request);
                }
                completedCount++;
            } catch (TaskCanceledException ex) {
                if (savedReview != null && firstReview == null) firstReview = savedReview;
                throw ex;
            } catch (StorageAccessException | IOException ex) {
                failedCount++;
                if (savedReview != null) {
                    pendingCount++;
                    if (firstReview == null) firstReview = savedReview;
                }
                recordFailedTransfer(operation, item, targetPath, request, ex);
            } finally {
                context.resultReference(firstReview);
            }
        }

        String message = transferMessage(operation, completedCount, failedCount);
        if (pendingCount > 0) {
            message += " " + pendingCount + " directory merge(s) awaiting review.";
            return failedCount == 0 ? TaskOutcome.pending(message) : TaskOutcome.partial(message);
        }
        return failedCount == 0 ? TaskOutcome.complete(message) : TaskOutcome.partial(message);
    }

    private StorageProgressListener cancellationListener(TaskContext context) {
        return TaskContext.transferProgress(context);
    }

    private TaskOutcome runMoveToTrash(
            List<FileItem> items,
            RequestSnapshot request,
            TaskContext context
    ) throws IOException {
        context.setTotalItems(items.size());
        int completedCount = 0;
        int failedCount = 0;
        for (FileItem item : items) {
            context.checkCanceled();
            context.message("Moving " + item.name() + " to trash.");
            try {
                TrashRecord record = trashService.moveVaultPathToTrash(item.path());
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
                completedCount++;
                context.incrementProcessedItems();
            } catch (TaskCanceledException ex) {
                throw ex;
            } catch (StorageAccessException | IOException ex) {
                failedCount++;
                activityLogService.record(
                        "TRASH_MOVE",
                        request.actor(),
                        request.ip(),
                        item.path(),
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
        fileLifecycleService.recordMove(request.actor(), request.ip(), item.path(), newPath, "Moved item");
    }

    private void finishCopiedItem(TransferBufferItem item, String newPath, String targetPath, RequestSnapshot request) {
        fileLifecycleService.recordCopy(
                request.actor(),
                request.ip(),
                item.path(),
                newPath,
                "Copied item to " + targetPath
        );
    }

    private void recordFailedTransfer(
            TransferOperation operation,
            TransferBufferItem item,
            String targetPath,
            RequestSnapshot request,
            Exception failure
    ) {
        fileLifecycleService.recordFailedTransfer(
                operation.activityType(),
                request.actor(),
                request.ip(),
                item.path(),
                targetPath,
                "Could not " + operation.label().toLowerCase(Locale.ROOT) + " item",
                failure
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
