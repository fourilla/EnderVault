package io.github.fourilla.endervault.web.api.v1.notification;

import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationCenterApiController {

    private static final int PREVIEW_LIMIT = 5;
    private static final DateTimeFormatter CREATED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final PendingFileDecisionService pendingFileDecisionService;

    public NotificationCenterApiController(PendingFileDecisionService pendingFileDecisionService) {
        this.pendingFileDecisionService = pendingFileDecisionService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public NotificationCenterResponse notifications() throws IOException {
        List<PendingFileDecision> decisions = pendingFileDecisionService.list();
        List<NotificationCenterItem> items = decisions.stream()
                .limit(PREVIEW_LIMIT)
                .map(NotificationCenterItem::from)
                .toList();
        return new NotificationCenterResponse(true, decisions.size(), items, "/admin/pending-decisions");
    }

    public record NotificationCenterResponse(
            boolean ok,
            int actionableCount,
            List<NotificationCenterItem> items,
            String reviewAllHref
    ) {
    }

    public record NotificationCenterItem(
            String id,
            String type,
            String title,
            String detail,
            String createdLabel,
            String href
    ) {
        static NotificationCenterItem from(PendingFileDecision decision) {
            String destination = decision.destinationPath() == null || decision.destinationPath().isBlank()
                    ? "/"
                    : "/" + decision.destinationPath();
            return new NotificationCenterItem(
                    decision.id(),
                    "PENDING_FILE_DECISION",
                    decision.originalFilename(),
                    decision.source().label() + " awaiting review in " + destination,
                    CREATED_AT_FORMATTER.format(decision.createdAt()),
                    "/admin/pending-decisions#decision-" + decision.id()
            );
        }
    }
}
