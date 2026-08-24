package io.github.fourilla.endervault.filerequest;

import io.github.fourilla.endervault.activity.ActivityLogEntry;
import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.filerequest.FileRequestPendingDecisionObserver.FileRequestUploadReference;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.pending.PendingFileDecisionSource;
import io.github.fourilla.endervault.upload.ResumableUploadService;
import io.github.fourilla.endervault.upload.ResumableUploadCoordinator;
import io.github.fourilla.endervault.upload.ResumableUploadSession;
import io.github.fourilla.endervault.upload.ResumableUploadSource;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class FileRequestOperationsService {

    private static final int ACTIVITY_HISTORY_LIMIT = 50;

    private final FileRequestService fileRequestService;
    private final ResumableUploadService resumableUploadService;
    private final ResumableUploadCoordinator resumableUploadCoordinator;
    private final PendingFileDecisionService pendingFileDecisionService;
    private final ActivityLogService activityLogService;

    public FileRequestOperationsService(
            FileRequestService fileRequestService,
            ResumableUploadService resumableUploadService,
            ResumableUploadCoordinator resumableUploadCoordinator,
            PendingFileDecisionService pendingFileDecisionService,
            ActivityLogService activityLogService
    ) {
        this.fileRequestService = fileRequestService;
        this.resumableUploadService = resumableUploadService;
        this.resumableUploadCoordinator = resumableUploadCoordinator;
        this.pendingFileDecisionService = pendingFileDecisionService;
        this.activityLogService = activityLogService;
    }

    public FileRequestSnapshot snapshot(String id) throws IOException {
        FileRequest request = fileRequestService.require(id);
        return new FileRequestSnapshot(
                request,
                activeUploads(request.id()),
                pendingDecisions(request.id()),
                activityLogService.recentByMetadata("requestId", request.id(), ACTIVITY_HISTORY_LIMIT)
        );
    }

    public FileRequest revoke(String id) throws IOException {
        FileRequest request = fileRequestService.require(id);
        fileRequestService.revoke(request.id());
        return request;
    }

    public FileRequest delete(String id) throws IOException {
        FileRequestSnapshot snapshot = snapshot(id);
        if (snapshot.request().usable(Instant.now())) {
            throw new FileRequestDependencyException("Revoke an active file request before deleting it.");
        }
        requireNoDependencies(snapshot);
        fileRequestService.delete(id);
        return snapshot.request();
    }

    public CancelUploadsResult cancelActiveUploads(String id) throws IOException {
        FileRequestSnapshot snapshot = snapshot(id);
        int canceled = 0;
        for (ResumableUploadSession session : snapshot.activeUploads()) {
            resumableUploadCoordinator.deleteProtocolDataIfPresent(session);
            resumableUploadService.cancel(session.id());
            canceled++;
        }
        return new CancelUploadsResult(snapshot.request(), canceled);
    }

    public ExpiredDeletionResult deleteExpired(Instant now) throws IOException {
        int deleted = 0;
        List<String> skippedRequestIds = new ArrayList<>();
        for (FileRequest request : fileRequestService.list()) {
            if (!request.expired(now)) {
                continue;
            }
            FileRequestSnapshot snapshot = snapshot(request.id());
            if (snapshot.hasDependencies()) {
                skippedRequestIds.add(request.id());
                continue;
            }
            fileRequestService.delete(request.id());
            deleted++;
        }
        return new ExpiredDeletionResult(deleted, List.copyOf(skippedRequestIds));
    }

    private List<ResumableUploadSession> activeUploads(String requestId) throws IOException {
        return resumableUploadService.list().stream()
                .filter(session -> session.source() == ResumableUploadSource.FILE_REQUEST)
                .filter(session -> requestId.equals(session.sourceReference()))
                .filter(session -> !session.status().terminal())
                .toList();
    }

    private List<PendingFileDecision> pendingDecisions(String requestId) throws IOException {
        return pendingFileDecisionService.list().stream()
                .filter(decision -> decision.source() == PendingFileDecisionSource.FILE_REQUEST)
                .filter(decision -> belongsToRequest(decision, requestId))
                .toList();
    }

    private boolean belongsToRequest(PendingFileDecision decision, String requestId) {
        try {
            return requestId.equals(FileRequestUploadReference.parse(decision.sourceReference()).requestId());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private void requireNoDependencies(FileRequestSnapshot snapshot) {
        if (!snapshot.activeUploads().isEmpty()) {
            throw new FileRequestDependencyException("Cancel active uploads before deleting this file request.");
        }
        if (!snapshot.pendingDecisions().isEmpty()) {
            throw new FileRequestDependencyException("Resolve pending files before deleting this file request.");
        }
    }

    public record FileRequestSnapshot(
            FileRequest request,
            List<ResumableUploadSession> activeUploads,
            List<PendingFileDecision> pendingDecisions,
            List<ActivityLogEntry> activityHistory
    ) {
        public boolean hasDependencies() {
            return !activeUploads.isEmpty() || !pendingDecisions.isEmpty();
        }
    }

    public record CancelUploadsResult(FileRequest request, int canceled) {
    }

    public record ExpiredDeletionResult(int deleted, List<String> skippedRequestIds) {
        public int skipped() {
            return skippedRequestIds.size();
        }
    }
}
