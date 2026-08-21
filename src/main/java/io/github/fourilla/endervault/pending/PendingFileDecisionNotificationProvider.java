package io.github.fourilla.endervault.pending;

import io.github.fourilla.endervault.notificationcenter.ActionRequiredItem;
import io.github.fourilla.endervault.notificationcenter.ActionRequiredProvider;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PendingFileDecisionNotificationProvider implements ActionRequiredProvider {

    private final PendingFileDecisionService pendingFileDecisionService;

    public PendingFileDecisionNotificationProvider(PendingFileDecisionService pendingFileDecisionService) {
        this.pendingFileDecisionService = pendingFileDecisionService;
    }

    @Override
    public List<ActionRequiredItem> items() throws IOException {
        return pendingFileDecisionService.list().stream()
                .map(this::toActionRequiredItem)
                .toList();
    }

    @Override
    public String reviewAllHref() {
        return "/admin/pending-decisions";
    }

    private ActionRequiredItem toActionRequiredItem(PendingFileDecision decision) {
        String destination = decision.destinationPath() == null || decision.destinationPath().isBlank()
                ? "/"
                : "/" + decision.destinationPath();
        return new ActionRequiredItem(
                decision.id(),
                "PENDING_FILE_DECISION",
                decision.originalFilename(),
                decision.source().label() + " awaiting review in " + destination,
                decision.createdAt(),
                "/admin/pending-decisions#decision-" + decision.id()
        );
    }
}
