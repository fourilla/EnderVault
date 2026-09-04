package io.github.fourilla.endervault.web.api.v1.notification;

import io.github.fourilla.endervault.notificationcenter.ActionRequiredItem;
import io.github.fourilla.endervault.notificationcenter.NotificationCenterService;
import io.github.fourilla.endervault.notificationcenter.NotificationCenterService.NotificationCenterSnapshot;
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

    private final NotificationCenterService notificationCenterService;

    public NotificationCenterApiController(NotificationCenterService notificationCenterService) {
        this.notificationCenterService = notificationCenterService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public NotificationCenterResponse notifications() throws IOException {
        NotificationCenterSnapshot snapshot = notificationCenterService.snapshot(PREVIEW_LIMIT);
        List<NotificationCenterItem> items = snapshot.items().stream()
                .map(NotificationCenterItem::from)
                .toList();
        return new NotificationCenterResponse(
                true,
                snapshot.actionableCount(),
                items,
                snapshot.reviewAllHref()
        );
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
        static NotificationCenterItem from(ActionRequiredItem item) {
            return new NotificationCenterItem(
                    item.id(),
                    item.type(),
                    item.title(),
                    item.detail(),
                    CREATED_AT_FORMATTER.format(item.createdAt()),
                    item.href()
            );
        }
    }
}
