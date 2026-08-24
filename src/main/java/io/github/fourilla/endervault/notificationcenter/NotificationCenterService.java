package io.github.fourilla.endervault.notificationcenter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class NotificationCenterService {

    private final List<ActionRequiredProvider> providers;

    public NotificationCenterService(List<ActionRequiredProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    public NotificationCenterSnapshot snapshot(int previewLimit) throws IOException {
        List<ActionRequiredItem> allItems = new ArrayList<>();
        Set<String> reviewAllHrefs = new LinkedHashSet<>();
        for (ActionRequiredProvider provider : providers) {
            List<ActionRequiredItem> providerItems = provider.items();
            allItems.addAll(providerItems);
            if (!providerItems.isEmpty()) {
                reviewAllHrefs.add(provider.reviewAllHref());
            }
        }
        allItems.sort(Comparator.comparing(ActionRequiredItem::createdAt).reversed());
        String reviewAllHref = reviewAllHrefs.size() == 1
                ? reviewAllHrefs.iterator().next()
                : null;
        return new NotificationCenterSnapshot(
                allItems.size(),
                allItems.stream().limit(Math.max(0, previewLimit)).toList(),
                reviewAllHref
        );
    }

    public record NotificationCenterSnapshot(
            int actionableCount,
            List<ActionRequiredItem> items,
            String reviewAllHref
    ) {
    }
}
