package io.github.fourilla.endervault.directorytransfer;

import io.github.fourilla.endervault.filecommit.DurableJsonFileWriter;
import io.github.fourilla.endervault.pending.PendingFileDecisionRepository.MergeClaim;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Deletes only pre-approved metadata paths. Caller holds the review store lock. */
@Component
public class DirectoryTransferRecordCleanup {
    private final DirectoryTransferReviewStore reviews;
    private final ObjectMapper mapper;
    private final DurableJsonFileWriter writer;

    public DirectoryTransferRecordCleanup(DirectoryTransferReviewStore reviews, ObjectMapper mapper) {
        this.reviews = reviews;
        this.mapper = mapper;
        writer = new DurableJsonFileWriter(mapper);
    }

    boolean eligible(String id) throws IOException {
        var review = remainingReview(id);
        if (review == null) return false;
        var run = run(id);
        if (run == null || run.paused() || run.recoveryRequired() || run.revision() != review.revision()
                || run.phase() != DirectoryTransferRun.Phase.COMPLETE && run.phase() != DirectoryTransferRun.Phase.ABANDONED
                || hasReferences(id)) return false;
        var root = reviews.inspectionRoot();
        boolean frozen = Files.exists(root.resolve("executions").resolve(id + ".json"), LinkOption.NOFOLLOW_LINKS);
        if (frozen) {
            var approval = read(root.resolve("executions").resolve(id + ".json"), DirectoryTransferReview.class);
            if (!approval.equals(review) || !approval.fullyReviewed()) return false;
        } else if (run.phase() != DirectoryTransferRun.Phase.ABANDONED) return false;
        var results = new java.util.HashMap<String, DirectoryTransferResult>();
        for (var item : review.plan().items()) {
            var result = optional(root.resolve("results").resolve(id).resolve(item.id() + ".json"), DirectoryTransferResult.class);
            if (result != null) {
                DirectoryTransferReviewStore.validateResult(item, result);
                results.put(item.id(), result);
            }
        }
        DirectoryTransferReviewStore.validateResultTargets(review, results);
        var items = review.plan().items().stream().map(DirectoryTransferPlan.Item::id).toList();
        validateLayout(new Approval(id, items));
        for (var item : review.plan().items()) {
            var result = results.get(item.id());
            var completion = optional(root.resolve("completion").resolve(id).resolve(item.id() + ".json"), DirectoryTransferCompletion.class);
            if (completion != null && !item.id().equals(completion.itemId())) return false;
            if (!frozen && (result != null || completion != null)) return false;
            if (result != null && result.status() == DirectoryTransferResult.Status.NEEDS_REVIEW) return false;
            if (completion != null && completion.phase() != DirectoryTransferCompletion.Phase.COMPLETE
                    && completion.phase() != DirectoryTransferCompletion.Phase.RETAINED) return false;
            if (run.phase() == DirectoryTransferRun.Phase.COMPLETE && (result == null || completion == null)) return false;
            if (run.phase() == DirectoryTransferRun.Phase.COMPLETE
                    && review.plan().operation() == DirectoryTransferPlan.Operation.PENDING
                    && completion.phase() != DirectoryTransferCompletion.Phase.COMPLETE) return false;
            if (result != null && result.status() == DirectoryTransferResult.Status.PUBLISHED) {
                if (completion == null) return false;
                if (review.plan().operation() == DirectoryTransferPlan.Operation.COPY
                        && completion.phase() != DirectoryTransferCompletion.Phase.COMPLETE) return false;
            }
            if (completion != null && result == null) return false;
        }
        return true;
    }

    void approve(String id) throws IOException {
        approve(id, null);
    }

    void approve(String id, MergeClaim claim) throws IOException {
        if (!eligible(id)) throw new IOException("Transfer records are not fully settled.");
        var review = remainingReview(id);
        var approval = new Approval(id, review.plan().items().stream().map(DirectoryTransferPlan.Item::id).toList(), claim);
        writer.write(marker(id), approval);
        writer.forceDirectory(reviews.inspectionRoot());
    }

    List<String> pending() throws IOException {
        Path directory = reviews.inspectionRoot().resolve("cleanup");
        DirectoryTransferPlanner.rejectLinks(directory);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var paths = Files.list(directory)) {
            return paths.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString().replaceFirst("\\.json$", "")).toList();
        }
    }

    void finish(String id) throws IOException {
        var approval = prepareFinish(id);
        for (String kind : List.of("results", "completion")) {
            Path directory = reviews.inspectionRoot().resolve(kind).resolve(id);
            for (String item : approval.items()) delete(directory.resolve(item + ".json"));
            delete(directory);
        }
        delete(reviews.inspectionRoot().resolve("executions").resolve(id + ".json"));
        delete(reviews.inspectionRoot().resolve("runs").resolve(id + ".json"));
        delete(reviews.inspectionRoot().resolve(id + ".json"));
        delete(marker(id));
    }

    Approval prepareFinish(String id) throws IOException {
        var approval = inspectApproval(id);
        // Also required before claim deletion: an earlier approval write may have failed during force.
        writer.forceDirectory(marker(id).getParent());
        writer.forceDirectory(reviews.inspectionRoot());
        return approval;
    }

    Approval inspectApproval(String id) throws IOException {
        var approval = approval(id);
        validateLayout(approval);
        return approval;
    }

    DirectoryTransferReview remainingReview(String id) throws IOException {
        uuid(id);
        Path path = reviews.inspectionRoot().resolve(id + ".json");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        var review = read(path, DirectoryTransferReview.class);
        if (!id.equals(review.plan().id())) throw new IOException("Cleanup review identity mismatch.");
        return review;
    }

    Approval approval(String id) throws IOException {
        var approval = read(marker(id), Approval.class);
        if (!id.equals(approval.id())) throw new IOException("Cleanup approval identity mismatch.");
        return approval;
    }

    private void validateLayout(Approval approval) throws IOException {
        Path root = reviews.inspectionRoot();
        Set<String> expected = new HashSet<>();
        for (String item : approval.items()) expected.add(item + ".json");
        for (String kind : List.of("results", "completion")) {
            Path directory = root.resolve(kind).resolve(approval.id());
            DirectoryTransferPlanner.rejectLinks(directory);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var paths = Files.list(directory)) {
                for (var path : (Iterable<Path>) paths::iterator) {
                    if (!expected.contains(path.getFileName().toString())) throw new IOException("Unexpected transfer item record.");
                    requireRegular(path);
                }
            }
        }
        for (Path path : List.of(root.resolve(approval.id() + ".json"),
                root.resolve("executions").resolve(approval.id() + ".json"),
                root.resolve("runs").resolve(approval.id() + ".json"))) {
            DirectoryTransferPlanner.rejectLinks(path);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) requireRegular(path);
        }
        if (hasReferences(approval.id())) {
            throw new IOException("Referenced transfer records must be retained.");
        }
    }

    private boolean hasReferences(String id) throws IOException {
        Path directory = reviews.inspectionRoot().resolve("successors");
        DirectoryTransferPlanner.rejectLinks(directory);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return false;
        try (var paths = Files.list(directory)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                var link = read(path, DirectoryTransferReviewStore.Successor.class);
                uuid(link.previous()); uuid(link.next());
                if (!path.getFileName().toString().equals(link.previous() + ".json")) throw new IOException("Invalid successor identity.");
                if (id.equals(link.previous()) || id.equals(link.next())) return true;
            }
        }
        return false;
    }

    List<String> ids() throws IOException {
        Path root = reviews.inspectionRoot();
        DirectoryTransferPlanner.rejectLinks(root);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var paths = Files.list(root)) {
            return paths.map(p -> p.getFileName().toString())
                    .filter(name -> name.matches("[0-9a-f-]{36}\\.json"))
                    .map(name -> name.substring(0, 36)).filter(id -> !reviews.cleanupPending(id)).sorted().toList();
        }
    }

    DirectoryTransferRun run(String id) throws IOException {
        uuid(id);
        var run = optional(reviews.inspectionRoot().resolve("runs").resolve(id + ".json"), DirectoryTransferRun.class);
        if (run != null && !id.equals(run.id())) throw new IOException("Invalid run identity.");
        return run;
    }

    java.util.Set<String> pendingIds(Path registry) throws IOException {
        var decisions = optional(registry, io.github.fourilla.endervault.pending.PendingFileDecision[].class);
        var ids = new HashSet<String>();
        if (decisions != null) for (var decision : decisions) {
            uuid(decision.id());
            if (!ids.add(decision.id())) throw new IOException("Duplicate pending identity.");
        }
        return ids;
    }

    java.util.Map<String, MergeClaim> claims(Path registry) throws IOException {
        Path directory = registry.getParent().resolve("pending-directory-merges");
        DirectoryTransferPlanner.rejectLinks(directory);
        var claims = new java.util.HashMap<String, MergeClaim>();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return claims;
        try (var paths = Files.list(directory)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                var claim = read(path, MergeClaim.class);
                uuid(claim.decision().id());
                if (!path.getFileName().toString().equals(claim.decision().id() + ".json")
                        || claims.put(claim.mergeId(), claim) != null) throw new IOException("Invalid pending claim identity.");
            }
        }
        return claims;
    }

    private <T> T optional(Path path, Class<T> type) throws IOException {
        DirectoryTransferPlanner.rejectLinks(path);
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? read(path, type) : null;
    }

    void delete(Path path) throws IOException {
        if (!path.normalize().startsWith(reviews.inspectionRoot())) throw new IOException("Invalid cleanup path.");
        DirectoryTransferPlanner.rejectLinks(path);
        Files.deleteIfExists(path);
        // A previous attempt may have deleted the entry but failed before forcing its parent.
        if (Files.isDirectory(path.getParent(), LinkOption.NOFOLLOW_LINKS)) writer.forceDirectory(path.getParent());
    }

    private Path marker(String id) throws IOException {
        uuid(id);
        var path = reviews.inspectionRoot().resolve("cleanup").resolve(id + ".json");
        DirectoryTransferPlanner.rejectLinks(path);
        return path;
    }

    private <T> T read(Path path, Class<T> type) throws IOException {
        return DirectoryTransferInspectionReader.read(mapper, path, type, 128 * 1024 * 1024);
    }

    private void requireRegular(Path path) throws IOException {
        DirectoryTransferPlanner.rejectLinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe cleanup record.");
    }

    private static void uuid(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid cleanup ID.");
    }

    record Approval(String id, List<String> items, MergeClaim claim) {
        Approval(String id, List<String> items) { this(id, items, null); }
        Approval {
            uuid(id);
            items = List.copyOf(items);
            if (items.isEmpty() || items.size() > 100_000 || new HashSet<>(items).size() != items.size()) throw new IllegalArgumentException("Invalid cleanup items.");
            items.forEach(DirectoryTransferRecordCleanup::uuid);
            if (claim != null && !id.equals(claim.mergeId())) throw new IllegalArgumentException("Invalid cleanup owner.");
        }
    }
}
