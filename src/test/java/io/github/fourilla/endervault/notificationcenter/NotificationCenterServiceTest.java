package io.github.fourilla.endervault.notificationcenter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationCenterServiceTest {

    @Test
    void mergesSortsAndLimitsProviderItems() throws Exception {
        ActionRequiredProvider olderProvider = provider(
                "/admin/older",
                new ActionRequiredItem(
                        "older",
                        "OLDER",
                        "Older item",
                        "detail",
                        Instant.parse("2026-08-20T00:00:00Z"),
                        "/admin/older/older"
                )
        );
        ActionRequiredProvider newerProvider = provider(
                "/admin/newer",
                new ActionRequiredItem(
                        "newer",
                        "NEWER",
                        "Newer item",
                        "detail",
                        Instant.parse("2026-08-21T00:00:00Z"),
                        "/admin/newer/newer"
                )
        );

        NotificationCenterService.NotificationCenterSnapshot snapshot =
                new NotificationCenterService(List.of(olderProvider, newerProvider)).snapshot(1);

        assertThat(snapshot.actionableCount()).isEqualTo(2);
        assertThat(snapshot.items()).extracting(ActionRequiredItem::id).containsExactly("newer");
        assertThat(snapshot.reviewAllHref()).isNull();
    }

    @Test
    void keepsSharedReviewPageWhenAllProvidersUseTheSameDestination() throws Exception {
        ActionRequiredItem first = new ActionRequiredItem(
                "first", "TYPE", "First", "detail", Instant.parse("2026-08-20T00:00:00Z"), "/admin/review"
        );
        ActionRequiredItem second = new ActionRequiredItem(
                "second", "TYPE", "Second", "detail", Instant.parse("2026-08-21T00:00:00Z"), "/admin/review"
        );
        NotificationCenterService service = new NotificationCenterService(List.of(
                provider("/admin/review", first),
                provider("/admin/review", second)
        ));

        NotificationCenterService.NotificationCenterSnapshot snapshot = service.snapshot(5);

        assertThat(snapshot.reviewAllHref()).isEqualTo("/admin/review");
    }

    @Test
    void ignoresReviewDestinationsFromProvidersWithoutItems() throws Exception {
        ActionRequiredItem pending = new ActionRequiredItem(
                "pending", "TYPE", "Pending", "detail", Instant.now(), "/admin/pending"
        );
        NotificationCenterService service = new NotificationCenterService(List.of(
                provider("/admin/pending", pending),
                provider("/admin/metadata")
        ));

        NotificationCenterService.NotificationCenterSnapshot snapshot = service.snapshot(5);

        assertThat(snapshot.reviewAllHref()).isEqualTo("/admin/pending");
    }

    private ActionRequiredProvider provider(String reviewAllHref, ActionRequiredItem... items) {
        return new ActionRequiredProvider() {
            @Override
            public List<ActionRequiredItem> items() {
                return List.of(items);
            }

            @Override
            public String reviewAllHref() {
                return reviewAllHref;
            }
        };
    }
}
