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
            FileRequestUploadReference reference = FileRequestUploadReference.parse(decision.sourceReference());
            fileRequestService.releaseAcceptedUpload(reference.requestId(), reference.uploadId(), decision.size());
        }
    }

    public record FileRequestUploadReference(String requestId, String uploadId) {

        public static String format(String requestId, String uploadId) {
            return requestId + ":" + uploadId;
        }

        public static FileRequestUploadReference parse(String value) {
            int separator = value == null ? -1 : value.indexOf(':');
            if (separator <= 0 || separator == value.length() - 1) {
                throw new IllegalArgumentException("Invalid file request upload reference.");
            }
            return new FileRequestUploadReference(value.substring(0, separator), value.substring(separator + 1));
        }
    }
}
