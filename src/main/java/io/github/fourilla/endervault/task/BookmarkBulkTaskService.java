package io.github.fourilla.endervault.task;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.auth.ClientIpResolver;
import io.github.fourilla.endervault.bookmark.BookmarkItem;
import io.github.fourilla.endervault.bookmark.BookmarkLogMetadata;
import io.github.fourilla.endervault.bookmark.BookmarkService;
import io.github.fourilla.endervault.bookmark.BookmarkService.BulkLinkInput;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.outbound.OutboundRouteStateService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.springframework.stereotype.Service;

@Service
public class BookmarkBulkTaskService {

    private final TaskManagerService taskManagerService;
    private final BookmarkService bookmarkService;
    private final ActivityLogService activityLogService;
    private final ClientIpResolver clientIpResolver;
    private final OutboundRouteStateService outboundRouteStateService;

    public BookmarkBulkTaskService(
            TaskManagerService taskManagerService,
            BookmarkService bookmarkService,
            ActivityLogService activityLogService,
            ClientIpResolver clientIpResolver,
            OutboundRouteStateService outboundRouteStateService
    ) {
        this.taskManagerService = taskManagerService;
        this.bookmarkService = bookmarkService;
        this.activityLogService = activityLogService;
        this.clientIpResolver = clientIpResolver;
        this.outboundRouteStateService = outboundRouteStateService;
    }

    public AppTask queueCreateLinks(
            String parentId,
            String bulkText,
            HttpServletRequest request
    ) throws IOException {
        List<BulkLinkInput> inputs = bookmarkService.parseBulkLinkInputs(bulkText);
        String normalizedParentId = bookmarkService.normalizeExistingParentId(parentId);
        NetworkRoute networkRoute = outboundRouteStateService.currentRoute();
        RequestSnapshot requestSnapshot = RequestSnapshot.from(request, clientIpResolver);
        String target = normalizedParentId == null ? "Bookmarks" : "Bookmark directory " + normalizedParentId;
        return taskManagerService.submit(
                TaskType.BOOKMARK_BULK_CREATE,
                "Add " + inputs.size() + " bookmark link(s)",
                target,
                requestSnapshot.actor(),
                requestSnapshot.ip(),
                context -> runCreateLinks(normalizedParentId, inputs, networkRoute, requestSnapshot, context)
        );
    }

    private TaskOutcome runCreateLinks(
            String parentId,
            List<BulkLinkInput> inputs,
            NetworkRoute networkRoute,
            RequestSnapshot request,
            TaskContext context
    ) throws IOException {
        context.setTotalItems(inputs.size());

        List<BookmarkItem> created = new ArrayList<>();
        int failedCount = 0;
        for (BulkLinkInput input : inputs) {
            context.checkCanceled();
            context.message("Adding " + taskItemLabel(input) + ".");
            try {
                created.add(bookmarkService.createLink(
                        parentId,
                        input.title(),
                        input.url(),
                        "",
                        context::canceled,
                        networkRoute
                ));
            } catch (CancellationException ex) {
                throw new TaskCanceledException();
            } catch (StorageAccessException | IOException ex) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new TaskCanceledException();
                }
                failedCount++;
            } finally {
                context.incrementProcessedItems();
            }
        }

        boolean success = failedCount == 0;
        String message = success
                ? "Created " + created.size() + " bookmark link(s)."
                : "Created " + created.size() + " bookmark link(s). " + failedCount + " failed.";
        activityLogService.record(
                "BOOKMARK_BULK_CREATE",
                request.actor(),
                request.ip(),
                null,
                null,
                success,
                message,
                BookmarkLogMetadata.bulk(inputs.size(), created, failedCount)
        );
        return success ? TaskOutcome.complete(message) : TaskOutcome.partial(message);
    }

    private String taskItemLabel(BulkLinkInput input) {
        String title = input.title() == null ? "" : input.title().trim();
        if (!title.isBlank()) {
            return truncate(title);
        }
        return truncate(input.url());
    }

    private String truncate(String value) {
        String normalized = value == null ? "bookmark" : value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80).trim() + "...";
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
