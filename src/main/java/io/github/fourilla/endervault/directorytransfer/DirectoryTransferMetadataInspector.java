package io.github.fourilla.endervault.directorytransfer;

import io.github.fourilla.endervault.metadata.*;
import io.github.fourilla.endervault.filecommit.FileCommitJournalStore;
import io.github.fourilla.endervault.filecommit.FileCommitJournalInspection;
import io.github.fourilla.endervault.filecommit.FileCommitOwnerType;
import io.github.fourilla.endervault.task.TaskContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Read-only diagnostics: never invoke registry readers that back up corrupt input. */
@Component
public class DirectoryTransferMetadataInspector implements MetadataInspector {
    private static final Set<String> SINGLE = Set.of("executions", "runs", "successors", "cleanup");
    private static final Set<String> ITEMS = Set.of("results", "completion");
    private static final int MAX_FILES = 1_000_000;
    private final DirectoryTransferReviewStore store;
    private final ObjectMapper mapper;
    private final FileCommitJournalStore journals;
    private final DirectoryTransferPendingInspector pending;

    public DirectoryTransferMetadataInspector(DirectoryTransferReviewStore store, ObjectMapper mapper,
            FileCommitJournalStore journals, DirectoryTransferPendingInspector pending) {
        this.store = store;
        this.mapper = mapper;
        this.journals = journals;
        this.pending = pending;
    }

    @Override public MetadataArea area() { return MetadataArea.DIRECTORY_MERGES; }
    @Override public List<MetadataIssue> inspect() throws IOException { return inspect(null); }
    @Override public List<MetadataIssue> inspect(TaskContext context) throws IOException {
        var issues = new ArrayList<>(inspectMergeRecords(context));
        for (var issue : pending.inspect(context)) {
            if (issues.size() >= 1000) break;
            issues.add(issue);
        }
        return List.copyOf(issues);
    }

    private List<MetadataIssue> inspectMergeRecords(TaskContext context) throws IOException {
        check(context);
        var issues = new ArrayList<MetadataIssue>();
        Path root = store.inspectionRoot();
        // Snapshot journals before taking the merge-store lock; never nest the journal lock inside it.
        var journalSnapshot = journals.inspectJournals();
        synchronized (store) {
            try { DirectoryTransferPlanner.rejectLinks(root); }
            catch (IOException | RuntimeException ex) {
                issue(issues, "directory-merges", "Merge metadata directory cannot be safely inspected");
                return issues;
            }
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                inspectJournals(root, journalSnapshot, issues, context);
                return List.copyOf(issues);
            }
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                issue(issues, "directory-merges", "Merge metadata root is not a directory");
                return issues;
            }
            var ids = new HashSet<String>();
            try (var paths = Files.walk(root, 3)) {
                var iterator = paths.iterator();
                int count = 0;
                while (iterator.hasNext()) {
                    check(context);
                    Path path = iterator.next();
                    if (path.equals(root)) continue;
                    if (++count > MAX_FILES) {
                        issue(issues, "directory-merges", "Inspection limit reached; remaining records were not checked");
                        return List.copyOf(issues);
                    }
                    Path relative = root.relativize(path);
                    String first = relative.getName(0).toString();
                    int depth = relative.getNameCount();
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        if (depth == 1 && (SINGLE.contains(first) || ITEMS.contains(first))) continue;
                        if (depth == 2 && ITEMS.contains(first) && uuid(relative.getName(1).toString())) {
                            ids.add(relative.getName(1).toString());
                            continue;
                        }
                    } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        String id = depth == 1 ? jsonId(first)
                                : depth == 2 && SINGLE.contains(first) ? jsonId(relative.getName(1).toString())
                                : depth == 3 && ITEMS.contains(first) && jsonId(relative.getName(2).toString()) != null
                                        ? relative.getName(1).toString() : null;
                        if (id != null && uuid(id)) { ids.add(id); continue; }
                    }
                    issue(issues, relative.toString(), "Unexpected or unsafe merge metadata entry");
                }
            }
            for (String id : ids) {
                check(context);
                inspectPlan(root, id, issues, context);
            }
            inspectJournals(root, journalSnapshot, issues, context);
        }
        return List.copyOf(issues);
    }

    private void inspectJournals(Path root, List<FileCommitJournalInspection> snapshot,
            List<MetadataIssue> issues, TaskContext context) {
        var groups = new java.util.HashMap<String, List<FileCommitJournalInspection>>();
        for (var inspected : snapshot) {
            check(context);
            // Unreadable journals cannot be assigned to a merge; the existing journal inspector reports them.
            if (!inspected.readable() || inspected.entry().manifest().owner().type() != FileCommitOwnerType.DIRECTORY_MERGE) continue;
            String[] owner = inspected.entry().manifest().owner().id().split(":", -1);
            if (owner.length != 2 || !uuid(owner[0]) || !uuid(owner[1])) {
                issue(issues, inspected.operationId(), "Journal has an invalid directory merge owner");
                continue;
            }
            groups.computeIfAbsent(owner[0], ignored -> new ArrayList<>()).add(inspected);
        }
        for (var group : groups.entrySet()) {
            check(context);
            String id = group.getKey();
            try {
                var review = read(root.resolve(id + ".json"), DirectoryTransferReview.class);
                var frozen = read(root.resolve("executions").resolve(id + ".json"), DirectoryTransferReview.class);
                var run = optional(root.resolve("runs").resolve(id + ".json"), DirectoryTransferRun.class);
                if (run != null && run.phase() == DirectoryTransferRun.Phase.ABANDONED) {
                    issue(issues, id, "Abandoned transfer still owns commit journals");
                }
                if (!id.equals(review.plan().id()) || !review.equals(frozen) || !frozen.fullyReviewed()) {
                    throw new IOException("Journal approval mismatch");
                }
                var items = review.plan().items().stream().collect(Collectors.toMap(DirectoryTransferPlan.Item::id, item -> item));
                var results = new java.util.HashMap<String, DirectoryTransferResult>();
                for (var item : review.plan().items()) {
                    check(context);
                    var result = optional(root.resolve("results").resolve(id).resolve(item.id() + ".json"), DirectoryTransferResult.class);
                    if (result != null) {
                        if (!item.id().equals(result.itemId())) throw new IOException("Result identity mismatch");
                        results.put(item.id(), result);
                    }
                }
                var index = new DirectoryTransferIndex(review.plan());
                var seen = new HashSet<String>();
                for (var inspected : group.getValue()) {
                    check(context);
                    String itemId = inspected.entry().manifest().owner().id().split(":", -1)[1];
                    try {
                        var item = items.get(itemId);
                        if (item == null || !seen.add(itemId)) throw new IOException("Unknown or duplicate journal owner");
                        DirectoryTransferJournalGuard.requireMatches(inspected.entry(), review, item, index.targetPath(item, results));
                        var result = results.get(itemId);
                        if (result != null && result.commitId() != null && !result.commitId().equals(inspected.operationId())) {
                            throw new IOException("Result refers to a different journal");
                        }
                        if (result != null && result.status() == DirectoryTransferResult.Status.PUBLISHED
                                && !result.targetPath().equals(inspected.entry().manifest().items().getFirst().targetPath())) {
                            throw new IOException("Published target differs from its journal");
                        }
                    } catch (IOException | RuntimeException ex) {
                        issue(issues, inspected.operationId(), "Journal does not match its approved merge item");
                    }
                }
            } catch (IOException | RuntimeException ex) {
                if (ex instanceof io.github.fourilla.endervault.task.TaskCanceledException canceled) throw canceled;
                issue(issues, id, "Merge journal has no readable matching approval or result context");
            }
        }
    }

    private void inspectPlan(Path root, String id, List<MetadataIssue> issues, TaskContext context) {
        try {
            if (store.cleanupPending(id)) {
                var approval = read(root.resolve("cleanup").resolve(id + ".json"), DirectoryTransferRecordCleanup.Approval.class);
                if (!id.equals(approval.id())) throw new IOException("Cleanup identity mismatch");
                issue(issues, id, "Completed transfer record cleanup was interrupted; scan Completed task records and repair eligible results");
                return;
            }
            Path reviewPath = root.resolve(id + ".json");
            if (!Files.isRegularFile(reviewPath, LinkOption.NOFOLLOW_LINKS)) {
                issue(issues, id, "Execution records remain without their merge review");
                return;
            }
            var review = read(reviewPath, DirectoryTransferReview.class);
            if (!id.equals(review.plan().id())) throw new IOException("Review identity mismatch");
            var frozen = optional(root.resolve("executions").resolve(id + ".json"), DirectoryTransferReview.class);
            var run = optional(root.resolve("runs").resolve(id + ".json"), DirectoryTransferRun.class);
            if (frozen != null && (!frozen.equals(review) || !frozen.fullyReviewed())) {
                issue(issues, id, "Execution approval does not match the saved review");
            }
            boolean abandoned = run != null && run.phase() == DirectoryTransferRun.Phase.ABANDONED;
            if (run != null && (!id.equals(run.id()) || run.revision() != review.revision() || !abandoned && frozen == null)) {
                issue(issues, id, "Merge run has no matching execution approval");
            }
            Map<String, DirectoryTransferPlan.Item> items = review.plan().items().stream()
                    .collect(Collectors.toMap(DirectoryTransferPlan.Item::id, item -> item));
            boolean complete = run != null && run.phase() == DirectoryTransferRun.Phase.COMPLETE;
            var resultIds = inspectItems(root, id, "results", items, frozen != null, complete, issues, context);
            var completionIds = inspectItems(root, id, "completion", items, frozen != null, complete, issues, context);
            boolean abandonedTransfer = abandoned && frozen != null;
            if (abandoned && (run.paused() || !abandonedTransfer && (frozen != null
                    || !resultIds.isEmpty() || !completionIds.isEmpty()))) {
                issue(issues, id, "Abandoned unstarted transfer contains execution records");
            }
            if (run != null && run.phase() == DirectoryTransferRun.Phase.ABANDONING
                    && frozen == null) {
                issue(issues, id, "Abandonment cleanup has no approved transfer");
            }
            if (abandonedTransfer) {
                for (String itemId : resultIds) {
                    var result = read(root.resolve("results").resolve(id).resolve(itemId + ".json"), DirectoryTransferResult.class);
                    if (result.status() != DirectoryTransferResult.Status.PUBLISHED) continue;
                    var done = optional(root.resolve("completion").resolve(id).resolve(itemId + ".json"), DirectoryTransferCompletion.class);
                    if (done == null || done.phase() != DirectoryTransferCompletion.Phase.COMPLETE
                            && !(review.plan().operation() != DirectoryTransferPlan.Operation.COPY
                                && done.phase() == DirectoryTransferCompletion.Phase.RETAINED)) {
                        issue(issues, id, "Abandoned transfer has unfinished published-item bookkeeping");
                    }
                }
            }
            if (run != null && run.phase() == DirectoryTransferRun.Phase.COMPLETE
                    && (!resultIds.containsAll(items.keySet()) || !completionIds.containsAll(items.keySet()))) {
                issue(issues, id, "Completed merge is missing per-item outcome records");
            }
            var next = optional(root.resolve("successors").resolve(id + ".json"), Successor.class);
            if (next != null) {
                if (!id.equals(next.previous()) || !uuid(next.next()) || run == null
                        || run.phase() != DirectoryTransferRun.Phase.NEEDS_REVIEW) {
                    issue(issues, id, "Invalid merge successor relationship");
                } else {
                    var successor = read(root.resolve(next.next() + ".json"), DirectoryTransferReview.class);
                    if (!next.next().equals(successor.plan().id())
                            || successor.plan().operation() != review.plan().operation()
                            || !successor.plan().sourceReference().equals(review.plan().sourceReference())
                            || !successor.plan().destinationPath().equals(review.plan().destinationPath())) {
                        issue(issues, id, "Successor points to an unrelated merge review");
                    }
                    checkSuccessorChain(root, id, issues, context);
                }
            }
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof io.github.fourilla.endervault.task.TaskCanceledException canceled) throw canceled;
            issue(issues, id, "Merge records are unreadable or inconsistent");
        }
    }

    private Set<String> inspectItems(Path root, String id, String kind, Map<String, DirectoryTransferPlan.Item> items,
            boolean frozen, boolean complete, List<MetadataIssue> issues, TaskContext context) throws IOException {
        Path directory = root.resolve(kind).resolve(id);
        DirectoryTransferPlanner.rejectLinks(directory);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return Set.of();
        var found = new HashSet<String>();
        try (var paths = Files.list(directory)) {
            for (var path : (Iterable<Path>) paths::iterator) {
                check(context);
                String itemId = jsonId(path.getFileName().toString());
                if (itemId == null) continue; // Already reported by layout enumeration.
                try {
                    var item = items.get(itemId);
                    if (item == null || !frozen) {
                        issue(issues, id + "/" + kind + "/" + itemId, "Item record has no matching approved plan item");
                        continue;
                    }
                    if (kind.equals("results")) {
                        var result = read(path, DirectoryTransferResult.class);
                        if (!itemId.equals(result.itemId()) || result.status() == DirectoryTransferResult.Status.PUBLISHED
                                && result.target().kind() != item.source().kind()) throw new IOException("Invalid result identity");
                        if (complete && result.status() == DirectoryTransferResult.Status.NEEDS_REVIEW) {
                            throw new IOException("Completed run contains unresolved result");
                        }
                    } else {
                        var completion = read(path, DirectoryTransferCompletion.class);
                        if (!itemId.equals(completion.itemId()) || complete
                                && completion.phase() != DirectoryTransferCompletion.Phase.COMPLETE
                                && completion.phase() != DirectoryTransferCompletion.Phase.RETAINED) {
                            throw new IOException("Invalid completion identity or phase");
                        }
                    }
                    found.add(itemId);
                } catch (IOException | RuntimeException ex) {
                    issue(issues, id + "/" + kind + "/" + itemId, "Merge item record is unreadable or inconsistent");
                }
            }
        }
        return found;
    }

    private void checkSuccessorChain(Path root, String id, List<MetadataIssue> issues, TaskContext context) throws IOException {
        var visited = new HashSet<String>();
        while (visited.size() < 256 && visited.add(id)) {
            check(context);
            var next = optional(root.resolve("successors").resolve(id + ".json"), Successor.class);
            if (next == null) return;
            if (!uuid(next.next())) throw new IOException("Invalid successor");
            id = next.next();
        }
        issue(issues, id, "Merge successor chain is cyclic or exceeds the inspection limit");
    }

    private <T> T optional(Path path, Class<T> type) throws IOException {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? read(path, type) : null;
    }

    private <T> T read(Path path, Class<T> type) throws IOException {
        int limit = type == DirectoryTransferReview.class || type == DirectoryTransferRecordCleanup.Approval.class
                ? 128 * 1024 * 1024 : 1024 * 1024;
        return DirectoryTransferInspectionReader.read(mapper, path, type, limit);
    }

    private void issue(List<MetadataIssue> issues, String subject, String title) {
        if (issues.size() >= 1000) return;
        issues.add(new MetadataIssue(area(), MetadataIssueSeverity.WARNING, MetadataIssueAction.NONE,
                subject, title, "Directory merge metadata: " + subject,
                "Preserve merge records, journals and staged data. Review manually before removing anything."));
    }

    private static String jsonId(String name) {
        return name.endsWith(".json") && uuid(name.substring(0, name.length() - 5))
                ? name.substring(0, name.length() - 5) : null;
    }
    private static boolean uuid(String id) {
        try { return id != null && java.util.UUID.fromString(id).toString().equals(id); }
        catch (IllegalArgumentException ex) { return false; }
    }
    private static void check(TaskContext context) { if (context != null) context.checkCanceled(); }
    private record Successor(String previous, String next) {}
    @Override public String repair(MetadataIssueAction action, String subject) {
        throw new IllegalArgumentException("Directory merge records require manual review.");
    }
}
