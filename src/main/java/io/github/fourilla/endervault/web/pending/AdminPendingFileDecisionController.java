package io.github.fourilla.endervault.web.pending;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminPendingFileDecisionController {

    private static final DateTimeFormatter CREATED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final PendingFileDecisionService pendingFileDecisionService;

    public AdminPendingFileDecisionController(PendingFileDecisionService pendingFileDecisionService) {
        this.pendingFileDecisionService = pendingFileDecisionService;
    }

    @GetMapping("/admin/pending-decisions")
    public String decisions(Model model) throws IOException {
        List<PendingFileDecisionView> decisions = pendingFileDecisionService.list().stream()
                .map(PendingFileDecisionView::from)
                .toList();
        model.addAttribute("decisions", decisions);
        return "pending-file-decisions";
    }

    public record PendingFileDecisionView(
            PendingFileDecision decision,
            String sourceLabel,
            String sizeLabel,
            String createdLabel,
            String destinationLabel
    ) {
        static PendingFileDecisionView from(PendingFileDecision decision) {
            return new PendingFileDecisionView(
                    decision,
                    decision.source().label(),
                    ByteSizeFormatter.humanSize(decision.size()),
                    CREATED_AT_FORMATTER.format(decision.createdAt()),
                    decision.destinationPath() == null || decision.destinationPath().isBlank()
                            ? "/"
                            : "/" + decision.destinationPath()
            );
        }
    }
}
