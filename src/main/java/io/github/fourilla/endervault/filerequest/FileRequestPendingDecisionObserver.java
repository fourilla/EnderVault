package io.github.fourilla.endervault.filerequest;

import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionAction;
import io.github.fourilla.endervault.pending.PendingFileDecisionResolutionObserver;
import io.github.fourilla.endervault.pending.PendingFileDecisionSource;
import org.springframework.stereotype.Component;

@Component
public class FileRequestPendingDecisionObserver implements PendingFileDecisionResolutionObserver {

    private final FileRequestService fileRequestService;

    public FileRequestPendingDecisionObserver(FileRequestService fileRequestService) {
        this.fileRequestService = fileRequestService;
    }

    @Override
    public boolean supports(PendingFileDecision decision) {
        return decision.source() == PendingFileDecisionSource.FILE_REQUEST
                && decision.sourceReference() != null;
    }

    @Override
    public void afterResolved(
            PendingFileDecision decision,
            PendingFileDecisionAction action,
            boolean discarded
    ) throws Exception {
        if (discarded) {
            fileRequestService.releaseAcceptedUpload(decision.sourceReference(), decision.size());
        }
    }
}
