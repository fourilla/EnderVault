package io.github.fourilla.endervault.directorymerge;

import io.github.fourilla.endervault.metadata.*;
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
public class DirectoryMergeMetadataInspector implements MetadataInspector {
    private static final Set<String> SINGLE = Set.of("executions", "runs", "successors");
    private static final Set<String> ITEMS = Set.of("results", "completion");
    private static final int MAX_FILES = 1_000_000;
    private final DirectoryMergeReviewStore store;
    private final ObjectMapper mapper;

    public DirectoryMergeMetadataInspector(DirectoryMergeReviewStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @Override public MetadataArea area() { return MetadataArea.DIRECTORY_MERGES; }
    @Override public List<MetadataIssue> inspect() throws IOException { return inspect(null); }
    @Override public List<MetadataIssue> inspect(TaskContext context) throws IOException {
        check(context);
        var issues = new ArrayList<MetadataIssue>();
        Path root = store.inspectionRoot();
        synchronized (store) {
            try { DirectoryMergePlanner.rejectLinks(root); }
            catch (IOException | RuntimeException ex) {
                issue(issues, "directory-merges", "Merge metadata directory cannot be safely inspected");
                return issues;
            }
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
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
        }
        return List.copyOf(issues);
    }

    private void inspectPlan(Path root, String id, List<MetadataIssue> issues, TaskContext context) {
        try {
            Path reviewPath = root.resolve(id + ".json");
            if (!Files.isRegularFile(reviewPath, LinkOption.NOFOLLOW_LINKS)) {
                issue(issues, id, "Execution records remain without their merge review");
                return;
            }
            var review = read(reviewPath, DirectoryMergeReview.class);
            if (!id.equals(review.plan().id())) throw new IOException("Review identity mismatch");
            var frozen = optional(root.resolve("executions").resolve(id + ".json"), DirectoryMergeReview.class);
            var run = optional(root.resolve("runs").resolve(id + ".json"), DirectoryMergeRun.class);
            if (frozen != null && (!frozen.equals(review) || !frozen.fullyReviewed())) {
                issue(issues, id, "Execution approval does not match the saved review");
            }
            if (run != null && (!id.equals(run.id()) || run.revision() != review.revision() || frozen == null)) {
                issue(issues, id, "Merge run has no matching execution approval");
            }
            Map<String, DirectoryMergePlan.Item> items = review.plan().items().stream()
                    .collect(Collectors.toMap(DirectoryMergePlan.Item::id, item -> item));
            boolean complete = run != null && run.phase() == DirectoryMergeRun.Phase.COMPLETE;
            var resultIds = inspectItems(root, id, "results", items, frozen != null, complete, issues, context);
            var completionIds = inspectItems(root, id, "completion", items, frozen != null, complete, issues, context);
            if (run != null && run.phase() == DirectoryMergeRun.Phase.COMPLETE
                    && (!resultIds.containsAll(items.keySet()) || !completionIds.containsAll(items.keySet()))) {
                issue(issues, id, "Completed merge is missing per-item outcome records");
            }
            var next = optional(root.resolve("successors").resolve(id + ".json"), Successor.class);
            if (next != null) {
                if (!id.equals(next.previous()) || !uuid(next.next()) || run == null
                        || run.phase() != DirectoryMergeRun.Phase.NEEDS_REVIEW) {
                    issue(issues, id, "Invalid merge successor relationship");
                } else {
                    var successor = read(root.resolve(next.next() + ".json"), DirectoryMergeReview.class);
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

    private Set<String> inspectItems(Path root, String id, String kind, Map<String, DirectoryMergePlan.Item> items,
            boolean frozen, boolean complete, List<MetadataIssue> issues, TaskContext context) throws IOException {
        Path directory = root.resolve(kind).resolve(id);
        DirectoryMergePlanner.rejectLinks(directory);
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
                        var result = read(path, DirectoryMergeResult.class);
                        if (!itemId.equals(result.itemId()) || result.status() == DirectoryMergeResult.Status.PUBLISHED
                                && result.target().kind() != item.source().kind()) throw new IOException("Invalid result identity");
                        if (complete && result.status() == DirectoryMergeResult.Status.NEEDS_REVIEW) {
                            throw new IOException("Completed run contains unresolved result");
                        }
                    } else {
                        var completion = read(path, DirectoryMergeCompletion.class);
                        if (!itemId.equals(completion.itemId()) || complete
                                && completion.phase() != DirectoryMergeCompletion.Phase.COMPLETE
                                && completion.phase() != DirectoryMergeCompletion.Phase.RETAINED) {
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
        DirectoryMergePlanner.rejectLinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular record");
        int limit = type == DirectoryMergeReview.class ? 128 * 1024 * 1024 : 1024 * 1024;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Record exceeds inspection limit");
            T value = mapper.readValue(bytes, type);
            if (value == null) throw new IOException("Empty record");
            return value;
        }
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
