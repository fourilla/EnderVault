package io.github.fourilla.endervault.directorymerge;

import io.github.fourilla.endervault.metadata.*;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionRepository;
import io.github.fourilla.endervault.task.TaskContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Cross-checks ownership without invoking registry recovery or deleting retained claims. */
@Component
public class DirectoryMergePendingInspector {
    private final DirectoryMergeReviewStore store;
    private final PendingFileDecisionRepository pending;
    private final ObjectMapper mapper;
    private final DirectoryMergePendingPreparationService preparation;
    private final DirectoryMergePendingReplanningService replanning;
    private final DirectoryMergePendingExecutionService execution;

    public DirectoryMergePendingInspector(DirectoryMergeReviewStore store, PendingFileDecisionRepository pending,
            ObjectMapper mapper, DirectoryMergePendingPreparationService preparation,
            DirectoryMergePendingReplanningService replanning, DirectoryMergePendingExecutionService execution) {
        this.store = store;
        this.pending = pending;
        this.mapper = mapper;
        this.preparation = preparation;
        this.replanning = replanning;
        this.execution = execution;
    }

    public List<MetadataIssue> inspect(TaskContext context) throws IOException {
        check(context);
        // These services do not call each other. Wait for their multi-record transitions before
        // taking repository/store locks; never acquire these monitors while holding a store lock.
        synchronized (preparation) {
            synchronized (replanning) {
                synchronized (execution) {
                    synchronized (pending) {
                        synchronized (store) {
                            check(context);
                            try { return inspectStable(context); }
                            catch (IOException | RuntimeException ex) {
                                rethrowCancellation(ex);
                                var issues = new ArrayList<MetadataIssue>();
                                issue(issues, "pending-directory-merges", "Pending ownership inspection could not be completed safely");
                                return List.copyOf(issues);
                            }
                        }
                    }
                }
            }
        }
    }

    private List<MetadataIssue> inspectStable(TaskContext context) throws IOException {
        var issues = new ArrayList<MetadataIssue>();
        Path registry = pending.inspectionPath();
        Path claimsRoot = registry.getParent().resolve("pending-directory-merges");
        var current = new HashMap<String, PendingFileDecision>();
        var claims = new HashMap<String, PendingFileDecisionRepository.MergeClaim>();
        try {
            DirectoryMergePlanner.rejectLinks(registry);
            if (Files.exists(registry, LinkOption.NOFOLLOW_LINKS)) {
                for (var decision : read(registry, PendingFileDecision[].class)) {
                    check(context);
                    if (decision == null || !uuid(decision.id()) || current.put(decision.id(), decision) != null) {
                        throw new IOException("Invalid pending registry identity");
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            rethrowCancellation(ex);
            issue(issues, "pending-file-decisions", "Pending registry is unreadable; ownership comparison was not completed");
            return issues;
        }
        try {
            DirectoryMergePlanner.rejectLinks(claimsRoot);
            if (Files.exists(claimsRoot, LinkOption.NOFOLLOW_LINKS)) {
                try (var paths = Files.list(claimsRoot)) {
                    int count = 0;
                    for (var path : (Iterable<Path>) paths::iterator) {
                        check(context);
                        if (++count > 100_000) throw new IOException("Claim inspection limit reached");
                        String filename = path.getFileName().toString();
                        String id = filename.endsWith(".json") ? filename.substring(0, filename.length() - 5) : "";
                        try {
                            if (!uuid(id)) throw new IOException("Invalid claim filename");
                            var claim = read(path, PendingFileDecisionRepository.MergeClaim.class);
                            if (!id.equals(claim.decision().id())) throw new IOException("Claim identity changed");
                            claims.put(id, claim);
                            inspectClaim(claim, current.get(id), issues, context);
                        } catch (IOException | RuntimeException ex) {
                            rethrowCancellation(ex);
                            issue(issues, filename, "Pending merge claim is unreadable or inconsistent");
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            rethrowCancellation(ex);
            issue(issues, "pending-directory-merges", "Pending claims could not be fully inspected");
            return issues;
        }
        // Reverse check catches removed claims, including completed merges whose claims are recovery evidence.
        Path root = store.inspectionRoot();
        DirectoryMergePlanner.rejectLinks(root);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return issues;
        try (var paths = Files.list(root)) {
            int count = 0;
            for (var path : (Iterable<Path>) paths::iterator) {
                check(context);
                if (++count > 100_000) {
                    issue(issues, "directory-merges", "Pending review comparison limit reached");
                    break;
                }
                String name = path.getFileName().toString();
                if (!name.endsWith(".json") || !uuid(name.substring(0, name.length() - 5))) continue;
                try {
                    var review = read(path, DirectoryMergeReview.class);
                    if (review.plan().operation() != DirectoryMergePlan.Operation.PENDING) continue;
                    var claim = claims.get(review.plan().sourceReference());
                    if (claim == null || !connected(review.plan().id(), claim.mergeId(), context)
                            && !connected(claim.mergeId(), review.plan().id(), context)) {
                        issue(issues, review.plan().id(), "Pending review has no matching owner claim or successor chain");
                    }
                } catch (IOException | RuntimeException ex) {
                    rethrowCancellation(ex);
                    issue(issues, name, "Pending review ownership could not be verified");
                }
            }
        }
        return List.copyOf(issues);
    }

    private void inspectClaim(PendingFileDecisionRepository.MergeClaim claim, PendingFileDecision current,
            List<MetadataIssue> issues, TaskContext context) throws IOException {
        var decision = claim.decision();
        if (current != null && !current.equals(decision)) throw new IOException("Pending snapshot changed");
        var review = review(claim.mergeId());
        String destination = decision.destinationPath().isEmpty() ? decision.originalFilename()
                : decision.destinationPath() + "/" + decision.originalFilename();
        if (review.plan().operation() != DirectoryMergePlan.Operation.PENDING
                || !decision.id().equals(review.plan().sourceReference())
                || !destination.equals(review.plan().destinationPath())) throw new IOException("Claim plan changed");
        var run = optional(store.inspectionRoot().resolve("runs").resolve(claim.mergeId() + ".json"), DirectoryMergeRun.class);
        if (run != null && (!claim.mergeId().equals(run.id()) || run.revision() != review.revision())) {
            throw new IOException("Pending run identity changed");
        }
        if (current == null && (run == null || run.phase() != DirectoryMergeRun.Phase.OWNER_COMPLETING
                && run.phase() != DirectoryMergeRun.Phase.COMPLETE)) {
            issue(issues, decision.id(), "Pending record is missing before owner completion");
        }
        if (current != null && run != null && run.phase() == DirectoryMergeRun.Phase.COMPLETE) {
            issue(issues, decision.id(), "Completed pending merge still has a live pending record");
        }
        // An old claim with a durable successor is an interrupted, retryable handoff, not an orphan.
        connected(claim.mergeId(), null, context);
    }

    private boolean connected(String start, String target, TaskContext context) throws IOException {
        var visited = new HashSet<String>();
        var previous = review(start);
        while (visited.size() < 256 && visited.add(start)) {
            check(context);
            if (start.equals(target)) return true;
            var next = optional(store.inspectionRoot().resolve("successors").resolve(start + ".json"), Successor.class);
            if (next == null) return false;
            if (!start.equals(next.previous()) || !uuid(next.next())) throw new IOException("Invalid successor");
            var successor = review(next.next());
            if (successor.plan().operation() != previous.plan().operation()
                    || !successor.plan().sourceReference().equals(previous.plan().sourceReference())
                    || !successor.plan().destinationPath().equals(previous.plan().destinationPath())) {
                throw new IOException("Unrelated successor");
            }
            start = next.next();
            previous = successor;
        }
        throw new IOException("Cyclic or excessive successor chain");
    }

    private DirectoryMergeReview review(String id) throws IOException {
        if (!uuid(id)) throw new IOException("Invalid review ID");
        var review = read(store.inspectionRoot().resolve(id + ".json"), DirectoryMergeReview.class);
        if (!id.equals(review.plan().id())) throw new IOException("Review identity changed");
        return review;
    }

    private <T> T read(Path path, Class<T> type) throws IOException {
        int limit = type == DirectoryMergeReview.class || type == PendingFileDecision[].class
                ? 128 * 1024 * 1024 : 1024 * 1024;
        return DirectoryMergeInspectionReader.read(mapper, path, type, limit);
    }
    private <T> T optional(Path path, Class<T> type) throws IOException {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? read(path, type) : null;
    }
    private static boolean uuid(String id) {
        try { return id != null && UUID.fromString(id).toString().equals(id); }
        catch (IllegalArgumentException ex) { return false; }
    }
    private static void check(TaskContext context) { if (context != null) context.checkCanceled(); }
    private static void rethrowCancellation(Exception ex) {
        if (ex instanceof io.github.fourilla.endervault.task.TaskCanceledException canceled) throw canceled;
    }
    private static void issue(List<MetadataIssue> issues, String subject, String title) {
        if (issues.size() < 1000) issues.add(new MetadataIssue(MetadataArea.DIRECTORY_MERGES,
                MetadataIssueSeverity.WARNING, MetadataIssueAction.NONE, subject, title,
                "Pending directory merge ownership: " + subject,
                "Preserve pending claims, merge records and staged data. Review manually before removing anything."));
    }
    private record Successor(String previous, String next) {}
}
