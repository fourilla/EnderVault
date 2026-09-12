package io.github.fourilla.endervault.directorymerge;

import io.github.fourilla.endervault.common.JsonRegistry;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filecommit.DurableJsonFileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class DirectoryMergeReviewStore {
    private final Path root;
    private final ObjectMapper mapper;
    private final DurableJsonFileWriter writer;

    Path inspectionRoot() { return root; }

    public DirectoryMergeReviewStore(ObjectMapper mapper, NasProperties properties) {
        this.mapper = mapper;
        writer = new DurableJsonFileWriter(mapper);
        Path vault = properties.getStorage().getRoot().toAbsolutePath().normalize();
        String metadata = properties.getStorage().getMetadataDirectory();
        if (metadata == null || metadata.isBlank() || metadata.contains("/") || metadata.contains("\\")
                || metadata.equals(".") || metadata.equals("..") || metadata.contains(":")) {
            throw new StorageAccessException("Invalid metadata directory.");
        }
        root = vault.resolve(metadata).resolve("directory-merges");
    }

    public synchronized DirectoryMergeReview create(DirectoryMergePlan plan) throws IOException {
        Path path = path(plan.id());
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new StorageAccessException("Directory merge review already exists.");
        DirectoryMergeReview review = new DirectoryMergeReview(plan, 0, Map.of());
        writer.write(path, review);
        return review;
    }

    public synchronized DirectoryMergeReview require(String id) throws IOException {
        Path path = path(id);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException("Directory merge review not found.");
        DirectoryMergeReview review = new JsonRegistry<DirectoryMergeReview>(mapper, path, new TypeReference<>() {},
                () -> null, JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW).read();
        if (review == null || !id.equals(review.plan().id())) throw new StorageAccessException("Invalid directory merge review.");
        return review;
    }

    /** A stale modal must not overwrite another session's decisions. */
    public synchronized DirectoryMergeReview choose(String id, long expectedRevision,
            Map<String, DirectoryMergeReview.Choice> updates) throws IOException {
        DirectoryMergeReview current = require(id);
        if (Files.exists(executionPath(id), LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageAccessException("Directory merge execution has started. Create a new review for changed items.");
        }
        if (current.revision() != expectedRevision) throw new StorageAccessException("Directory merge decisions changed. Reload the review.");
        Map<String, DirectoryMergeReview.Choice> choices = new HashMap<>(current.choices());
        choices.putAll(updates);
        DirectoryMergeReview next = new DirectoryMergeReview(current.plan(), Math.addExact(current.revision(), 1), choices);
        writer.write(path(id), next);
        return next;
    }

    /** An execution keeps the same approval even after a process restart. */
    public synchronized DirectoryMergeReview freeze(String id, long expectedRevision) throws IOException {
        DirectoryMergeReview review = require(id);
        if (review.revision() != expectedRevision || !review.fullyReviewed()) {
            throw new StorageAccessException("Directory merge review is incomplete or changed. Reload the review.");
        }
        Path execution = executionPath(id);
        if (Files.exists(execution, LinkOption.NOFOLLOW_LINKS)) {
            DirectoryMergeReview frozen = new JsonRegistry<DirectoryMergeReview>(mapper, execution,
                    new TypeReference<>() {}, () -> null, JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW).read();
            if (!review.equals(frozen)) throw new StorageAccessException("Directory merge execution approval changed.");
            return frozen;
        }
        writer.write(execution, review);
        writer.forceDirectory(root);
        return review;
    }

    private Path executionPath(String id) throws IOException {
        path(id);
        Path execution = root.resolve("executions").resolve(id + ".json");
        DirectoryMergePlanner.rejectLinks(execution);
        return execution;
    }

    public synchronized Map<String, DirectoryMergeResult> results(DirectoryMergeReview review) throws IOException {
        Path directory = resultDirectory(review.plan().id());
        Map<String, DirectoryMergeResult> results = new HashMap<>();
        for (var item : review.plan().items()) {
            Path file = directory.resolve(item.id() + ".json");
            DirectoryMergePlanner.rejectLinks(file);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;
            DirectoryMergeResult result = new JsonRegistry<DirectoryMergeResult>(mapper, file,
                    new TypeReference<>() {}, () -> null, JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW).read();
            validateResult(item, result);
            results.put(item.id(), result);
        }
        var index = new DirectoryMergeIndex(review.plan());
        for (var item : review.plan().items()) {
            var result = results.get(item.id());
            if (result == null || result.status() != DirectoryMergeResult.Status.PUBLISHED) continue;
            String expected = index.targetPath(item, results);
            if (review.choices().get(item.id()) == DirectoryMergeReview.Choice.KEEP_BOTH) {
                String expectedParent = expected.substring(0, Math.max(0, expected.lastIndexOf('/')));
                String actualParent = result.targetPath().substring(0, Math.max(0, result.targetPath().lastIndexOf('/')));
                if (!expectedParent.equals(actualParent)) throw new StorageAccessException("Merge result parent does not match its review.");
            } else if (!expected.equals(result.targetPath())) {
                throw new StorageAccessException("Merge result target does not match its review.");
            }
        }
        return Map.copyOf(results);
    }

    /** One immutable file per item avoids rewriting a growing 100,000-item ledger on each commit. */
    synchronized void recordResult(DirectoryMergeReview review, DirectoryMergePlan.Item item,
            DirectoryMergeResult result) throws IOException {
        validateResult(item, result);
        Path directory = resultDirectory(review.plan().id());
        Path file = directory.resolve(item.id() + ".json");
        DirectoryMergePlanner.rejectLinks(file);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            DirectoryMergeResult existing = new JsonRegistry<DirectoryMergeResult>(mapper, file,
                    new TypeReference<>() {}, () -> null, JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW).read();
            if (!result.equals(existing)) throw new StorageAccessException("Merge result already exists.");
            return;
        }
        writer.write(file, result);
        writer.forceDirectory(directory.getParent());
        writer.forceDirectory(root);
    }

    private Path resultDirectory(String id) throws IOException {
        path(id);
        Path directory = root.resolve("results").resolve(id);
        DirectoryMergePlanner.rejectLinks(directory);
        return directory;
    }

    synchronized DirectoryMergeCompletion completion(String planId, String itemId) throws IOException {
        Path file = completionPath(planId, itemId);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        var result = new JsonRegistry<DirectoryMergeCompletion>(mapper, file, new TypeReference<>() {},
                () -> null, JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW).read();
        if (result == null || !itemId.equals(result.itemId())) throw new StorageAccessException("Invalid merge completion record.");
        return result;
    }

    synchronized void recordCompletion(String planId, DirectoryMergeCompletion previous,
            DirectoryMergeCompletion next) throws IOException {
        if (!java.util.Objects.equals(previous, completion(planId, next.itemId()))) {
            throw new StorageAccessException("Directory merge completion changed.");
        }
        boolean allowed = previous == null
                ? next.phase() == DirectoryMergeCompletion.Phase.PREPARED || next.phase() == DirectoryMergeCompletion.Phase.RETAINED
                        || next.phase() == DirectoryMergeCompletion.Phase.NEEDS_REVIEW
                : switch (previous.phase()) {
                    case PREPARED -> next.phase() == DirectoryMergeCompletion.Phase.SOURCE_REMOVED
                            || next.phase() == DirectoryMergeCompletion.Phase.METADATA_APPLIED
                            || next.phase() == DirectoryMergeCompletion.Phase.RETAINED
                            || next.phase() == DirectoryMergeCompletion.Phase.NEEDS_REVIEW;
                    case SOURCE_REMOVED -> next.phase() == DirectoryMergeCompletion.Phase.METADATA_APPLIED
                            || next.phase() == DirectoryMergeCompletion.Phase.NEEDS_REVIEW;
                    case METADATA_APPLIED -> next.phase() == DirectoryMergeCompletion.Phase.COMPLETE
                            || next.phase() == DirectoryMergeCompletion.Phase.NEEDS_REVIEW;
                    default -> false;
                };
        if (!allowed) throw new StorageAccessException("Invalid merge completion transition.");
        Path file = completionPath(planId, next.itemId());
        writer.write(file, next);
        writer.forceDirectory(file.getParent().getParent());
        writer.forceDirectory(root);
    }

    private Path completionPath(String planId, String itemId) throws IOException {
        path(planId);
        if (itemId == null || !UUID.fromString(itemId).toString().equals(itemId)) throw new StorageAccessException("Invalid merge completion ID.");
        Path file = root.resolve("completion").resolve(planId).resolve(itemId + ".json");
        DirectoryMergePlanner.rejectLinks(file);
        return file;
    }

    private void validateResult(DirectoryMergePlan.Item item, DirectoryMergeResult result) {
        if (result == null || !item.id().equals(result.itemId())
                || result.status() == DirectoryMergeResult.Status.PUBLISHED && result.target().kind() != item.source().kind()) {
            throw new StorageAccessException("Invalid directory merge result.");
        }
    }

    public synchronized List<String> ids() throws IOException {
        ensureRoot();
        try (var paths = Files.list(root)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json"))
                    .map(name -> name.substring(0, 36)).sorted().toList();
        }
    }

    synchronized DirectoryMergeRun run(String id) throws IOException {
        Path file = runPath(id);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        var run = new JsonRegistry<DirectoryMergeRun>(mapper, file, new TypeReference<>() {},
                () -> null, JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW).read();
        if (run == null || !id.equals(run.id())) throw new StorageAccessException("Invalid directory merge run.");
        return run;
    }

    synchronized void saveRun(DirectoryMergeRun previous, DirectoryMergeRun next) throws IOException {
        if (!java.util.Objects.equals(previous, run(next.id()))) throw new StorageAccessException("Merge run changed.");
        boolean allowed = previous == null || switch (previous.phase()) {
            case PUBLISHING -> next.phase() == DirectoryMergeRun.Phase.PUBLISHING
                    || next.phase() == DirectoryMergeRun.Phase.FINALIZING;
            case FINALIZING -> next.phase() == DirectoryMergeRun.Phase.FINALIZING
                    || next.phase() == DirectoryMergeRun.Phase.OWNER_COMPLETING
                    || next.phase() == DirectoryMergeRun.Phase.COMPLETE
                    || next.phase() == DirectoryMergeRun.Phase.NEEDS_REVIEW;
            case OWNER_COMPLETING -> next.phase() == DirectoryMergeRun.Phase.OWNER_COMPLETING
                    || next.phase() == DirectoryMergeRun.Phase.COMPLETE;
            default -> false;
        };
        if (previous != null && (previous.revision() != next.revision() || !allowed)) {
            throw new StorageAccessException("Invalid merge run transition.");
        }
        if (previous == null && next.phase() != DirectoryMergeRun.Phase.PUBLISHING) {
            throw new StorageAccessException("Invalid initial merge run.");
        }
        writer.write(runPath(next.id()), next);
        writer.forceDirectory(root);
    }

    synchronized List<String> runIds() throws IOException {
        ensureRoot();
        Path directory = root.resolve("runs");
        DirectoryMergePlanner.rejectLinks(directory);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var paths = Files.list(directory)) {
            return paths.map(p -> p.getFileName().toString())
                    .filter(name -> name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json"))
                    .map(name -> name.substring(0, 36)).sorted().toList();
        }
    }

    synchronized List<String> reviewIds() throws IOException {
        ensureRoot();
        try (var paths = Files.list(root)) {
            return paths.map(p -> p.getFileName().toString())
                    .filter(name -> name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json"))
                    .map(name -> name.substring(0, 36)).sorted().toList();
        }
    }

    synchronized boolean frozen(String id) throws IOException {
        return Files.exists(executionPath(id), LinkOption.NOFOLLOW_LINKS);
    }

    private Path runPath(String id) throws IOException {
        path(id);
        Path file = root.resolve("runs").resolve(id + ".json");
        DirectoryMergePlanner.rejectLinks(file);
        return file;
    }

    synchronized String successor(String id) throws IOException {
        Path file = successorPath(id);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        var value = new JsonRegistry<Successor>(mapper, file, new TypeReference<>() {}, () -> null,
                JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW).read();
        if (value == null || !id.equals(value.previous())) throw new StorageAccessException("Invalid merge successor.");
        path(value.next());
        return value.next();
    }

    synchronized void recordSuccessor(String id, String next) throws IOException {
        var existing = successor(id);
        if (existing != null) {
            if (!existing.equals(next)) throw new StorageAccessException("Merge already has a successor.");
            return;
        }
        var old = require(id);
        var replacement = require(next);
        var run = run(id);
        if (run == null || run.phase() != DirectoryMergeRun.Phase.NEEDS_REVIEW
                || old.plan().operation() != replacement.plan().operation()
                || !old.plan().sourceReference().equals(replacement.plan().sourceReference())
                || !old.plan().destinationPath().equals(replacement.plan().destinationPath())
                || !replacement.choices().isEmpty() || Files.exists(executionPath(next), LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageAccessException("Invalid pending merge successor.");
        }
        writer.write(successorPath(id), new Successor(id, next));
        writer.forceDirectory(root);
    }

    private Path successorPath(String id) throws IOException {
        path(id);
        Path file = root.resolve("successors").resolve(id + ".json");
        DirectoryMergePlanner.rejectLinks(file);
        return file;
    }

    private record Successor(String previous, String next) {}

    private Path path(String id) throws IOException {
        try {
            if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) { throw new StorageAccessException("Invalid directory merge id."); }
        ensureRoot();
        Path path = root.resolve(id + ".json");
        DirectoryMergePlanner.rejectLinks(path);
        return path;
    }

    private void ensureRoot() throws IOException {
        DirectoryMergePlanner.rejectLinks(root);
        Files.createDirectories(root);
        writer.forceDirectory(root.getParent());
    }
}
