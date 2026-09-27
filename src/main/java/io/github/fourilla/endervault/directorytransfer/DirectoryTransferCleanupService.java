package io.github.fourilla.endervault.directorytransfer;

import io.github.fourilla.endervault.filecommit.FileCommitJournalStore;
import io.github.fourilla.endervault.filecommit.FileCommitOwnerType;
import io.github.fourilla.endervault.pending.PendingFileDecisionRepository;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.task.TaskManagerService;
import io.github.fourilla.endervault.task.TaskStatus;
import io.github.fourilla.endervault.task.TaskContext;
import io.github.fourilla.endervault.task.DirectoryTransferRecordsReleased;
import java.io.IOException;
import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** No age-based eviction: unsettled ownership or unreadable evidence always retains records. */
@Service
public class DirectoryTransferCleanupService {
    private static final Logger log = LoggerFactory.getLogger(DirectoryTransferCleanupService.class);
    private final DirectoryTransferReviewStore reviews;
    private final DirectoryTransferRecordCleanup records;
    private final DirectoryTransferService transfers;
    private final DirectoryTransferPendingExecutionService execution;
    private final PendingFileDecisionRepository pending;
    private final FileCommitJournalStore journals;
    private final TaskManagerService tasks;
    private final StorageService storage;

    public DirectoryTransferCleanupService(DirectoryTransferReviewStore reviews, DirectoryTransferRecordCleanup records,
            DirectoryTransferService transfers, DirectoryTransferPendingExecutionService execution,
            PendingFileDecisionRepository pending, FileCommitJournalStore journals, TaskManagerService tasks, StorageService storage) {
        this.reviews = reviews;
        this.records = records;
        this.transfers = transfers;
        this.execution = execution;
        this.pending = pending;
        this.journals = journals;
        this.tasks = tasks;
        this.storage = storage;
    }

    @EventListener
    public void onRecordsReleased(DirectoryTransferRecordsReleased event) {
        try { process(event.ids(), null, true); }
        catch (IOException | RuntimeException ex) {
            log.warn("Directory transfer record cleanup deferred: {}", ex.getClass().getSimpleName());
        }
    }

    int cleanup() throws IOException { return process(null, null, true).size(); }

    public java.util.List<Candidate> inspect(TaskContext context) throws IOException {
        return process(null, context, false);
    }

    public boolean cleanupOne(String id) throws IOException {
        if (id == null || !java.util.UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid task record ID.");
        return !process(java.util.Set.of(id), null, true).isEmpty();
    }

    private synchronized java.util.List<Candidate> process(java.util.Set<String> selected, TaskContext context, boolean remove) throws IOException {
        // Service -> repository -> review-store order. Never take journal locks under the review-store lock.
        synchronized (transfers) {
            synchronized (execution) {
                var journalSnapshot = journals.inspectJournals();
                if (journalSnapshot.stream().anyMatch(j -> !j.readable())) {
                    if (!remove) throw new IOException("Commit ownership could not be verified. No records were selected for cleanup.");
                    return java.util.List.of();
                }
                var blocked = new HashSet<String>();
                var pendingJournals = new HashSet<String>();
                for (var journal : journalSnapshot) {
                    var owner = journal.entry().manifest().owner();
                    if (owner.type() == FileCommitOwnerType.PENDING_FILE_DECISION) pendingJournals.add(owner.id());
                    if (owner.type() == FileCommitOwnerType.DIRECTORY_MERGE) {
                        var parts = owner.id().split(":", -1);
                        if (parts.length != 2 || !java.util.UUID.fromString(parts[0]).toString().equals(parts[0])
                                || !java.util.UUID.fromString(parts[1]).toString().equals(parts[1])) return java.util.List.of();
                        blocked.add(parts[0]);
                    }
                }
                var taskSnapshot = tasks.listTasks();
                for (var task : taskSnapshot) {
                    if (task.status().active() || task.status() == TaskStatus.PENDING) blocked.addAll(task.directoryTransferReviews());
                }
                synchronized (pending) {
                    var claims = records.claims(pending.inspectionPath());
                    var pendingIds = records.pendingIds(pending.inspectionPath());
                    synchronized (reviews) {
                        var result = new java.util.ArrayList<Candidate>();
                        var interrupted = selected == null ? records.pending()
                                : selected.stream().filter(reviews::cleanupPending).toList();
                        var candidates = selected == null ? records.ids() : selected.stream()
                                .filter(id -> !reviews.cleanupPending(id)).toList();
                        for (String id : interrupted) {
                            if (context != null) context.checkCanceled();
                            if (!blocked.contains(id)) {
                                var approval = records.inspectApproval(id);
                                if (approval.claim() == null && claims.containsKey(id)) continue;
                                if (approval.claim() != null) {
                                    if (pendingIds.contains(approval.claim().decision().id())
                                            || pendingJournals.contains(approval.claim().decision().id())
                                            || claims.containsKey(id) && !claims.get(id).equals(approval.claim())) continue;
                                    requireStagingAbsent(approval.claim());
                                }
                                var candidate = describe(id, records.remainingReview(id), "Cleanup interrupted");
                                if (remove) {
                                    records.prepareFinish(id);
                                    retireClaim(approval.claim(), pendingJournals);
                                    records.finish(id);
                                }
                                result.add(candidate);
                            }
                        }
                        for (String id : candidates) {
                            if (context != null) context.checkCanceled();
                            if (blocked.contains(id)) continue;
                            try {
                                var review = records.remainingReview(id);
                                if (review == null) continue;
                                if (review.plan().operation() == DirectoryTransferPlan.Operation.PENDING
                                        && pendingIds.contains(review.plan().sourceReference())) continue;
                                if (!records.eligible(id)) continue;
                                var claim = claims.get(id);
                                if (review.plan().operation() == DirectoryTransferPlan.Operation.PENDING) {
                                    if (pendingJournals.contains(review.plan().sourceReference())) continue;
                                    if (records.run(id).phase() == DirectoryTransferRun.Phase.COMPLETE) {
                                        if (claim == null || !claim.decision().id().equals(review.plan().sourceReference())) continue;
                                        String destination = claim.decision().destinationPath().isEmpty() ? claim.decision().originalFilename()
                                                : claim.decision().destinationPath() + "/" + claim.decision().originalFilename();
                                        if (!destination.equals(review.plan().destinationPath())) continue;
                                        requireStagingAbsent(claim);
                                    } else if (claim != null) continue;
                                } else if (claim != null) continue;
                                var candidate = describe(id, review, records.run(id).phase() == DirectoryTransferRun.Phase.COMPLETE ? "Completed" : "Abandoned");
                                if (remove) {
                                    records.approve(id, claim);
                                    records.prepareFinish(id);
                                    retireClaim(claim, pendingJournals);
                                    records.finish(id);
                                }
                                result.add(candidate);
                            } catch (IOException | RuntimeException ex) {
                                log.debug("Retaining directory transfer {} during cleanup: {}", id, ex.getClass().getSimpleName());
                            }
                        }
                        return java.util.List.copyOf(result);
                    }
                }
            }
        }
    }

    private Candidate describe(String id, DirectoryTransferReview review, String state) {
        if (review == null) return new Candidate(id, "Directory transfer", "", state);
        String operation = switch (review.plan().operation()) {
            case COPY -> "Copy";
            case MOVE -> "Move";
            case PENDING -> "Uploaded directory merge";
        };
        return new Candidate(id, operation, review.plan().destinationPath(), state);
    }

    public record Candidate(String id, String operation, String destination, String state) {}

    private void retireClaim(PendingFileDecisionRepository.MergeClaim claim, java.util.Set<String> pendingJournals) throws IOException {
        if (claim == null) return;
        if (pendingJournals.contains(claim.decision().id())) throw new IOException("Pending commit journal still references this owner.");
        requireStagingAbsent(claim);
        pending.retireCompletedMergeClaim(claim);
    }

    private void requireStagingAbsent(PendingFileDecisionRepository.MergeClaim claim) throws IOException {
        var staged = storage.resolveFileStagingFile(claim.decision().stagingFilename());
        if (java.nio.file.Files.exists(staged, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Completed upload still has staged data.");
        }
    }
}
