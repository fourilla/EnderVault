package io.github.fourilla.endervault.directorytransfer;

import io.github.fourilla.endervault.common.JsonRegistry;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filecommit.DurableJsonFileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class DirectoryTransferReviewStore {
    private final Path root;
    private final ObjectMapper mapper;
    private final DurableJsonFileWriter writer;
    private CachedMove cachedItemMove;

    Path inspectionRoot() { return root; }

    public DirectoryTransferReviewStore(ObjectMapper mapper, NasProperties properties) {
        this.mapper = mapper;
        writer = new DurableJsonFileWriter(mapper);
        Path vault = properties.getStorage().getRoot().toAbsolutePath().normalize();
        String metadata = properties.getStorage().getMetadataDirectory();
        if (metadata == null || metadata.isBlank() || metadata.contains("/") || metadata.contains("\\")
                || metadata.equals(".") || metadata.equals("..") || metadata.contains(":")) {
            throw new StorageAccessException("Invalid metadata directory.");
        }
        // Persistent namespace, independent of the Java package/type names.
        root = vault.resolve(metadata).resolve("directory-merges");
    }

    public synchronized DirectoryTransferReview create(DirectoryTransferPlan plan) throws IOException {
        Path path = path(plan.id());
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) || cleanupPending(plan.id())) throw new StorageAccessException("Directory merge review already exists.");
        DirectoryTransferReview review = new DirectoryTransferReview(plan, 0, Map.of());
        writer.write(path, review);
        return review;
    }

    public synchronized DirectoryTransferReview require(String id) throws IOException {
        Path path = path(id);
        if (cleanupPending(id)) throw new NoSuchFileException("Directory transfer records are being retired.");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException("Directory merge review not found.");
        DirectoryTransferReview review = new JsonRegistry<DirectoryTransferReview>(mapper, path, new TypeReference<>() {},
                () -> null, JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW).read();
        if (review == null || !id.equals(review.plan().id())) throw new StorageAccessException("Invalid directory merge review.");
        return review;
    }

    /** A stale modal must not overwrite another session's decisions. */
    public synchronized DirectoryTransferReview choose(String id, long expectedRevision,
            Map<String, DirectoryTransferReview.Choice> updates) throws IOException {
        DirectoryTransferReview current = require(id);
        rejectAbandoned(id);
        if (Files.exists(executionPath(id), LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageAccessException("Directory merge execution has started. Create a new review for changed items.");
        }
        if (current.revision() != expectedRevision) throw new StorageAccessException("Directory merge decisions changed. Reload the review.");
        Map<String, DirectoryTransferReview.Choice> choices = new HashMap<>(current.choices());
        choices.putAll(updates);
        DirectoryTransferReview next = new DirectoryTransferReview(current.plan(), Math.addExact(current.revision(), 1), choices);
        writer.write(path(id), next);
        return next;
    }

    public synchronized DirectoryTransferReview chooseAll(String id, long expectedRevision,
            DirectoryTransferReview.Choice choice) throws IOException {
        if (choice == null) throw new IllegalArgumentException("A choice is required.");
        DirectoryTransferReview current = require(id);
        Map<String, DirectoryTransferReview.Choice> updates = new HashMap<>();
        for (var item : current.plan().items()) {
            if (item.requiresDecision() && (choice != DirectoryTransferReview.Choice.OVERWRITE
                    || item.conflict() == DirectoryTransferPlan.Conflict.FILE_CONFLICT)) {
                updates.put(item.id(), choice);
            }
        }
        return choose(id, expectedRevision, updates);
    }

    /** Persist the stop even when shutdown or another caller interrupted this worker. */
    public synchronized void pauseRun(DirectoryTransferRun run, Throwable cancellation) throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            saveRun(run, new DirectoryTransferRun(run.id(), run.revision(), run.phase(), true, run.recoveryRequired(), run.strategy()));
        } catch (IOException | RuntimeException failure) {
            failure.addSuppressed(cancellation);
            throw failure;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Preserve recovery eligibility; failure recording must not replace the original IO error. */
    synchronized void recordFailure(DirectoryTransferRun run, IOException failure) {
        try {
            saveRun(run, new DirectoryTransferRun(run.id(), run.revision(), run.phase(), run.paused(), true, run.strategy()));
        } catch (IOException | RuntimeException recordingFailure) {
            failure.addSuppressed(recordingFailure);
        }
    }

    /** An execution keeps the same approval even after a process restart. */
    public synchronized DirectoryTransferReview freeze(String id, long expectedRevision) throws IOException {
        DirectoryTransferReview review = require(id);
        rejectAbandoned(id);
        if (review.revision() != expectedRevision || !review.fullyReviewed()) {
            throw new StorageAccessException("Directory merge review is incomplete or changed. Reload the review.");
        }
        Path execution = executionPath(id);
        if (Files.exists(execution, LinkOption.NOFOLLOW_LINKS)) {
            DirectoryTransferReview frozen = new JsonRegistry<DirectoryTransferReview>(mapper, execution,
                    new TypeReference<>() {}, () -> null, JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW).read();
            if (!review.equals(frozen)) throw new StorageAccessException("Directory merge execution approval changed.");
            return frozen;
        }
        writer.write(execution, review);
        writer.forceDirectory(root);
        return review;
    }

    /** A durable terminal record, not deletion: stale clients cannot resurrect this review. */
    synchronized void abandonUnstarted(String id, long revision) throws IOException {
        var review = require(id);
        if (review.revision() != revision) {
            throw new StorageAccessException("Directory transfer decisions changed. Reload the review.");
        }
        if (review.plan().operation() == DirectoryTransferPlan.Operation.PENDING && hasPredecessor(id)) {
            throw new StorageAccessException("A replanned upload may contain partially transferred data. Its owner must be retained.");
        }
        var run = run(id);
        boolean abandoned = run != null && run.phase() == DirectoryTransferRun.Phase.ABANDONED;
        if (run != null && (!abandoned || run.revision() != revision || run.paused()) || frozen(id) || successor(id) != null) {
            throw new StorageAccessException("This transfer has already started. Its remaining work cannot be abandoned here.");
        }
        if (!results(review).isEmpty()) throw new StorageAccessException("Transfer result records require inspection before abandonment.");
        for (var item : review.plan().items()) {
            if (completion(id, item.id()) != null) throw new StorageAccessException("Transfer completion records require inspection before abandonment.");
        }
        if (abandoned) {
            // A prior write may have installed the marker but failed while forcing its directory.
            writer.forceDirectory(runPath(id).getParent());
            writer.forceDirectory(root);
            return;
        }
        writer.write(runPath(id), new DirectoryTransferRun(id, revision, DirectoryTransferRun.Phase.ABANDONED, false));
        writer.forceDirectory(root);
    }

    private void rejectAbandoned(String id) throws IOException {
        var run = run(id);
        if (run != null && run.phase() == DirectoryTransferRun.Phase.ABANDONED) {
            throw new StorageAccessException("This directory transfer was abandoned.");
        }
    }

    synchronized boolean hasPredecessor(String id) throws IOException {
        path(id);
        Path directory = root.resolve("successors");
        DirectoryTransferPlanner.rejectLinks(directory);
        if (!Files.exists(directory)) return false;
        try (var paths = Files.list(directory)) {
            for (Path file : (Iterable<Path>) paths::iterator) {
                if (!file.getFileName().toString().endsWith(".json")) continue;
                DirectoryTransferPlanner.rejectLinks(file);
                var value = DirectoryTransferInspectionReader.read(mapper, file, Successor.class, 1024 * 1024);
                if (value == null || !file.getFileName().toString().equals(value.previous() + ".json")) {
                    throw new StorageAccessException("Invalid transfer successor record.");
                }
                path(value.previous());
                path(value.next());
                if (id.equals(value.next())) return true;
            }
        }
        return false;
    }

    private Path executionPath(String id) throws IOException {
        path(id);
        Path execution = root.resolve("executions").resolve(id + ".json");
        DirectoryTransferPlanner.rejectLinks(execution);
        return execution;
    }

    public synchronized Map<String, DirectoryTransferResult> results(DirectoryTransferReview review) throws IOException {
        Path directory = resultDirectory(review.plan().id());
        Map<String, DirectoryTransferResult> results = new HashMap<>();
        for (var item : review.plan().items()) {
            Path file = directory.resolve(item.id() + ".json");
            DirectoryTransferPlanner.rejectLinks(file);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;
            DirectoryTransferResult result = new JsonRegistry<DirectoryTransferResult>(mapper, file,
                    new TypeReference<>() {}, () -> null, JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW).read();
            validateResult(item, result);
            results.put(item.id(), result);
        }
        validateResultTargets(review, results);
        var move = move(review.plan().id());
        for (var item : review.plan().items()) {
            var result = results.get(item.id());
            if (result != null) validateResultProof(review, item, result, move);
        }
        return Map.copyOf(results);
    }

    static void validateResultTargets(DirectoryTransferReview review, Map<String, DirectoryTransferResult> results) {
        var index = new DirectoryTransferIndex(review.plan());
        for (var item : review.plan().items()) {
            var result = results.get(item.id());
            if (result == null || !result.hasTarget()) continue;
            String expected = index.targetPath(item, results);
            if (review.choices().get(item.id()) == DirectoryTransferReview.Choice.KEEP_BOTH) {
                String expectedParent = expected.substring(0, Math.max(0, expected.lastIndexOf('/')));
                String actualParent = result.targetPath().substring(0, Math.max(0, result.targetPath().lastIndexOf('/')));
                if (!expectedParent.equals(actualParent)) throw new StorageAccessException("Merge result parent does not match its review.");
            } else if (!expected.equals(result.targetPath())) {
                throw new StorageAccessException("Merge result target does not match its review.");
            }
        }
    }

    /** One immutable file per item avoids rewriting a growing 100,000-item ledger on each commit. */
    synchronized void recordResult(DirectoryTransferReview review, DirectoryTransferPlan.Item item,
            DirectoryTransferResult result) throws IOException {
        recordResult(review, item, result, itemMove(review.plan().id()));
    }

    synchronized void recordResult(DirectoryTransferReview review, DirectoryTransferPlan.Item item,
            DirectoryTransferResult result, DirectoryTransferMoveRecord move) throws IOException {
        validateResult(item, result);
        requireCurrentItemProof(review.plan().id(), move);
        validateResultProof(review, item, result, move);
        if (result.status() == DirectoryTransferResult.Status.MOVED
                && move.phase() != DirectoryTransferMoveRecord.Phase.METADATA_APPLIED
                && move.phase() != DirectoryTransferMoveRecord.Phase.COMPLETE) {
            throw new StorageAccessException("Directory move metadata has not completed.");
        }
        Path directory = resultDirectory(review.plan().id());
        Path file = directory.resolve(item.id() + ".json");
        DirectoryTransferPlanner.rejectLinks(file);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            DirectoryTransferResult existing = new JsonRegistry<DirectoryTransferResult>(mapper, file,
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
        DirectoryTransferPlanner.rejectLinks(directory);
        return directory;
    }

    synchronized DirectoryTransferMoveRecord move(String id) throws IOException {
        var move = readMove(id);
        if (move == null) return null;
        var review = require(id);
        requireMoveApproval(review);
        validateMove(review, run(id), move);
        return move;
    }

    /** Keep whole-tree moves out of copy/source-deletion engines unless no-effect fallback was recorded. */
    synchronized void requireItemwiseExecution(String id) throws IOException {
        var run = run(id);
        if (run == null || run.strategy() == DirectoryTransferRun.Strategy.ITEMWISE) return;
        var move = move(id);
        if (move == null || move.phase() != DirectoryTransferMoveRecord.Phase.FALLBACK) {
            throw new StorageAccessException("Whole-directory move cannot enter itemwise publication or source cleanup.");
        }
    }

    private DirectoryTransferMoveRecord readMove(String id) throws IOException {
        Path file = movePath(id);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        var move = new JsonRegistry<DirectoryTransferMoveRecord>(mapper, file, new TypeReference<>() {},
                () -> null, JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW).read();
        if (move == null || !id.equals(move.id())) throw new StorageAccessException("Invalid directory move evidence.");
        return move;
    }

    /** One-entry cache only for item ledgers; CAS, recovery inspection and cleanup always read durable files. */
    private DirectoryTransferMoveRecord itemMove(String id) throws IOException {
        var file = movePath(id);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (cachedItemMove != null && cachedItemMove.id().equals(id)) cachedItemMove = null;
            return null;
        }
        var before = moveStamp(file);
        if (cachedItemMove != null && cachedItemMove.id().equals(id) && cachedItemMove.stamp().equals(before)) {
            return cachedItemMove.move();
        }
        var move = readMove(id);
        var after = moveStamp(file);
        if (!before.equals(after)) throw new StorageAccessException("Directory move evidence changed while reading.");
        cachedItemMove = new CachedMove(id, move, after);
        return move;
    }

    private void requireCurrentItemProof(String id, DirectoryTransferMoveRecord proof) throws IOException {
        var current = itemMove(id);
        if (proof != current && !java.util.Objects.equals(proof, current)) {
            throw new StorageAccessException("Directory move item proof changed.");
        }
        // Reuse the caller's already validated immutable object after a disk reload of the same JSON.
        if (proof != null && proof != current) cachedItemMove = new CachedMove(id, proof, cachedItemMove.stamp());
    }

    private MoveStamp moveStamp(Path file) throws IOException {
        DirectoryTransferPlanner.rejectLinks(file);
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new StorageAccessException("Directory move evidence is not a regular file.");
        return new MoveStamp(attributes.size(), attributes.lastModifiedTime(), attributes.creationTime(), attributes.fileKey());
    }

    private record MoveStamp(long size, FileTime modified, FileTime created, Object fileKey) {}
    private record CachedMove(String id, DirectoryTransferMoveRecord move, MoveStamp stamp) {}

    synchronized void saveMove(DirectoryTransferMoveRecord previous, DirectoryTransferMoveRecord next) throws IOException {
        if (next == null || !java.util.Objects.equals(previous, move(next.id()))) {
            throw new StorageAccessException("Directory move evidence changed.");
        }
        var review = require(next.id());
        requireMoveApproval(review);
        validateMove(review, run(next.id()), next);
        boolean allowed = previous == null ? next.phase() == DirectoryTransferMoveRecord.Phase.INTENT
                : previous.equals(next) || previous.sameIntent(next) && switch (previous.phase()) {
                    case INTENT -> next.phase() == DirectoryTransferMoveRecord.Phase.RENAMED
                            || next.phase() == DirectoryTransferMoveRecord.Phase.FALLBACK
                            || next.phase() == DirectoryTransferMoveRecord.Phase.NEEDS_REVIEW;
                    case RENAMED -> next.phase() == DirectoryTransferMoveRecord.Phase.METADATA_APPLIED
                            || next.phase() == DirectoryTransferMoveRecord.Phase.NEEDS_REVIEW;
                    case METADATA_APPLIED -> next.phase() == DirectoryTransferMoveRecord.Phase.COMPLETE
                            || next.phase() == DirectoryTransferMoveRecord.Phase.NEEDS_REVIEW;
                    default -> previous.equals(next);
                };
        if (!allowed) throw new StorageAccessException("Invalid directory move transition.");
        var file = movePath(next.id());
        cachedItemMove = null;
        writer.write(file, next);
        writer.forceDirectory(file.getParent());
        writer.forceDirectory(root);
        cachedItemMove = new CachedMove(next.id(), next, moveStamp(file));
    }

    private void requireMoveApproval(DirectoryTransferReview review) throws IOException {
        Path execution = executionPath(review.plan().id());
        if (!Files.isRegularFile(execution, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageAccessException("Directory move has no frozen approval.");
        }
        var frozen = new JsonRegistry<DirectoryTransferReview>(mapper, execution, new TypeReference<>() {},
                () -> null, JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW).read();
        if (!review.equals(frozen) || !review.fullyReviewed()) {
            throw new StorageAccessException("Directory move approval changed.");
        }
    }

    static void validateMove(DirectoryTransferReview review, DirectoryTransferRun run, DirectoryTransferMoveRecord move) {
        var plan = review.plan();
        if (move == null || run == null || run.strategy() != DirectoryTransferRun.Strategy.WHOLE_DIRECTORY_MOVE
                || !plan.id().equals(move.id()) || !plan.id().equals(run.id())
                || move.revision() != review.revision() || run.revision() != review.revision()
                || plan.operation() != DirectoryTransferPlan.Operation.MOVE
                || !plan.sourceReference().equals(move.sourcePath()) || !plan.destinationPath().equals(move.targetPath())
                || !plan.excludedSources().isEmpty() || !plan.targetNames().isEmpty() || !review.choices().isEmpty()
                || plan.items().stream().anyMatch(item -> item.conflict() != DirectoryTransferPlan.Conflict.ADD)
                || !move.entryKeys().isEmpty() && (move.entryKeys().size() != plan.items().size()
                    || plan.items().stream().anyMatch(item -> !move.entryKeys().containsKey(item.relativePath())))
                || !DirectoryTransferMoveRecord.fingerprint(plan).equals(move.treeFingerprint())) {
            throw new StorageAccessException("Directory move evidence does not match its approved tree.");
        }
    }

    static void validateResultProof(DirectoryTransferReview review, DirectoryTransferPlan.Item item,
            DirectoryTransferResult result, DirectoryTransferMoveRecord move) {
        if (result.status() == DirectoryTransferResult.Status.MOVED) {
            if (move == null || !review.plan().id().equals(move.id()) || !move.id().equals(result.renameId())
                    || review.revision() != move.revision()
                    || !review.plan().sourceReference().equals(move.sourcePath())
                    || !review.plan().destinationPath().equals(move.targetPath())
                    || move.phase() != DirectoryTransferMoveRecord.Phase.METADATA_APPLIED
                        && move.phase() != DirectoryTransferMoveRecord.Phase.COMPLETE
                        && move.phase() != DirectoryTransferMoveRecord.Phase.NEEDS_REVIEW
                    || !item.source().equals(result.target())
                    || !move.entryKeys().isEmpty() && !move.entryKeys().containsKey(item.relativePath())
                    || !(item.relativePath().isEmpty() ? move.targetPath() : move.targetPath() + "/" + item.relativePath())
                            .equals(result.targetPath())) {
                throw new StorageAccessException("Moved item has no matching completed directory move proof.");
            }
        } else if (move != null && move.phase() != DirectoryTransferMoveRecord.Phase.FALLBACK
                && result.status() == DirectoryTransferResult.Status.PUBLISHED) {
            throw new StorageAccessException("Directory move cannot contain file publication results.");
        }
    }

    private Path movePath(String id) throws IOException {
        path(id);
        var file = root.resolve("moves").resolve(id + ".json");
        DirectoryTransferPlanner.rejectLinks(file);
        return file;
    }

    /** Whole-tree metadata is already applied; this path never removes a source or completes a journal. */
    synchronized void recordMovedCompletion(DirectoryTransferReview review, DirectoryTransferPlan.Item item) throws IOException {
        recordMovedCompletion(review, item, itemMove(review.plan().id()));
    }

    synchronized void recordMovedCompletion(DirectoryTransferReview review, DirectoryTransferPlan.Item item,
            DirectoryTransferMoveRecord move) throws IOException {
        requireCurrentItemProof(review.plan().id(), move);
        if (move == null || move.phase() != DirectoryTransferMoveRecord.Phase.METADATA_APPLIED
                && move.phase() != DirectoryTransferMoveRecord.Phase.COMPLETE) {
            throw new StorageAccessException("Directory move bookkeeping has no matching metadata proof.");
        }
        var file = resultDirectory(review.plan().id()).resolve(item.id() + ".json");
        DirectoryTransferPlanner.rejectLinks(file);
        var result = new JsonRegistry<DirectoryTransferResult>(mapper, file, new TypeReference<>() {},
                () -> null, JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW).read();
        validateResult(item, result);
        validateResultProof(review, item, result, move);
        if (result.status() != DirectoryTransferResult.Status.MOVED) {
            throw new StorageAccessException("Directory move bookkeeping requires a moved item.");
        }
        var next = new DirectoryTransferCompletion(item.id(), DirectoryTransferCompletion.Phase.COMPLETE, null);
        var previous = completion(review.plan().id(), item.id());
        if (previous != null) {
            if (!previous.equals(next)) throw new StorageAccessException("Directory move completion already exists.");
            return;
        }
        var completion = completionPath(review.plan().id(), item.id());
        writer.write(completion, next);
        writer.forceDirectory(completion.getParent().getParent());
        writer.forceDirectory(root);
    }

    synchronized DirectoryTransferCompletion completion(String planId, String itemId) throws IOException {
        Path file = completionPath(planId, itemId);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        var result = new JsonRegistry<DirectoryTransferCompletion>(mapper, file, new TypeReference<>() {},
                () -> null, JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW).read();
        if (result == null || !itemId.equals(result.itemId())) throw new StorageAccessException("Invalid merge completion record.");
        return result;
    }

    synchronized void recordCompletion(String planId, DirectoryTransferCompletion previous,
            DirectoryTransferCompletion next) throws IOException {
        if (!java.util.Objects.equals(previous, completion(planId, next.itemId()))) {
            throw new StorageAccessException("Directory merge completion changed.");
        }
        boolean allowed = previous == null
                ? next.phase() == DirectoryTransferCompletion.Phase.PREPARED || next.phase() == DirectoryTransferCompletion.Phase.RETAINED
                        || next.phase() == DirectoryTransferCompletion.Phase.NEEDS_REVIEW
                : switch (previous.phase()) {
                    case PREPARED -> next.phase() == DirectoryTransferCompletion.Phase.SOURCE_REMOVED
                            || next.phase() == DirectoryTransferCompletion.Phase.METADATA_APPLIED
                            || next.phase() == DirectoryTransferCompletion.Phase.RETAINED
                            || next.phase() == DirectoryTransferCompletion.Phase.NEEDS_REVIEW;
                    case SOURCE_REMOVED -> next.phase() == DirectoryTransferCompletion.Phase.METADATA_APPLIED
                            || next.phase() == DirectoryTransferCompletion.Phase.NEEDS_REVIEW;
                    case METADATA_APPLIED -> next.phase() == DirectoryTransferCompletion.Phase.COMPLETE
                            || next.phase() == DirectoryTransferCompletion.Phase.NEEDS_REVIEW;
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
        DirectoryTransferPlanner.rejectLinks(file);
        return file;
    }

    static void validateResult(DirectoryTransferPlan.Item item, DirectoryTransferResult result) {
        if (result == null || !item.id().equals(result.itemId())
                || result.hasTarget() && result.target().kind() != item.source().kind()) {
            throw new StorageAccessException("Invalid directory merge result.");
        }
    }

    public synchronized List<String> ids() throws IOException {
        ensureRoot();
        try (var paths = Files.list(root)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json"))
                    .map(name -> name.substring(0, 36)).filter(id -> !cleanupPending(id)).sorted().toList();
        }
    }

    synchronized DirectoryTransferRun run(String id) throws IOException {
        Path file = runPath(id);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        var run = new JsonRegistry<DirectoryTransferRun>(mapper, file, new TypeReference<>() {},
                () -> null, JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW).read();
        if (run == null || !id.equals(run.id())) throw new StorageAccessException("Invalid directory merge run.");
        return run;
    }

    synchronized void saveRun(DirectoryTransferRun previous, DirectoryTransferRun next) throws IOException {
        if (!java.util.Objects.equals(previous, run(next.id()))) throw new StorageAccessException("Merge run changed.");
        boolean allowed = previous == null || switch (previous.phase()) {
            case PUBLISHING -> next.phase() == DirectoryTransferRun.Phase.PUBLISHING
                    || next.phase() == DirectoryTransferRun.Phase.FINALIZING
                    || previous.strategy() == DirectoryTransferRun.Strategy.WHOLE_DIRECTORY_MOVE
                        && next.phase() == DirectoryTransferRun.Phase.NEEDS_REVIEW
                    || previous.paused() && next.phase() == DirectoryTransferRun.Phase.ABANDONING;
            case FINALIZING -> next.phase() == DirectoryTransferRun.Phase.FINALIZING
                    || next.phase() == DirectoryTransferRun.Phase.OWNER_COMPLETING
                    || next.phase() == DirectoryTransferRun.Phase.COMPLETE
                    || next.phase() == DirectoryTransferRun.Phase.NEEDS_REVIEW
                    || previous.paused() && next.phase() == DirectoryTransferRun.Phase.ABANDONING;
            case ABANDONING -> next.phase() == DirectoryTransferRun.Phase.ABANDONING
                    || next.phase() == DirectoryTransferRun.Phase.ABANDONED;
            case OWNER_COMPLETING -> next.phase() == DirectoryTransferRun.Phase.OWNER_COMPLETING
                    || next.phase() == DirectoryTransferRun.Phase.COMPLETE;
            default -> false;
        };
        if (previous != null && (previous.revision() != next.revision() || previous.strategy() != next.strategy() || !allowed)) {
            throw new StorageAccessException("Invalid merge run transition.");
        }
        if (previous == null && next.phase() != DirectoryTransferRun.Phase.PUBLISHING) {
            throw new StorageAccessException("Invalid initial merge run.");
        }
        if (next.strategy() == DirectoryTransferRun.Strategy.WHOLE_DIRECTORY_MOVE) {
            var review = require(next.id());
            if (review.plan().operation() != DirectoryTransferPlan.Operation.MOVE || next.revision() != review.revision()
                    || next.phase() == DirectoryTransferRun.Phase.ABANDONING || next.phase() == DirectoryTransferRun.Phase.ABANDONED
                    || previous == null && hasPredecessor(next.id())) {
                throw new StorageAccessException("Invalid whole-directory move run.");
            }
            requireMoveApproval(review);
            if (previous == null && (Files.exists(resultDirectory(next.id()), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(root.resolve("completion").resolve(next.id()), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(movePath(next.id()), LinkOption.NOFOLLOW_LINKS))) {
                throw new StorageAccessException("Existing transfer evidence cannot select a whole-directory strategy.");
            }
        }
        if (next.phase() == DirectoryTransferRun.Phase.ABANDONING
                && require(next.id()).plan().operation() == DirectoryTransferPlan.Operation.PENDING
                && (hasPredecessor(next.id()) || previous.phase() != DirectoryTransferRun.Phase.PUBLISHING
                    && previous.phase() != DirectoryTransferRun.Phase.ABANDONING)) {
            throw new StorageAccessException("This transfer phase does not support abandoning remaining work.");
        }
        writer.write(runPath(next.id()), next);
        writer.forceDirectory(root);
    }

    synchronized List<String> runIds() throws IOException {
        ensureRoot();
        Path directory = root.resolve("runs");
        DirectoryTransferPlanner.rejectLinks(directory);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var paths = Files.list(directory)) {
            return paths.map(p -> p.getFileName().toString())
                    .filter(name -> name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json"))
                    .map(name -> name.substring(0, 36)).filter(id -> !cleanupPending(id)).sorted().toList();
        }
    }

    synchronized List<String> reviewIds() throws IOException {
        ensureRoot();
        try (var paths = Files.list(root)) {
            return paths.map(p -> p.getFileName().toString())
                    .filter(name -> name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json"))
                    .map(name -> name.substring(0, 36)).filter(id -> !cleanupPending(id)).sorted().toList();
        }
    }

    synchronized boolean frozen(String id) throws IOException {
        return Files.exists(executionPath(id), LinkOption.NOFOLLOW_LINKS);
    }

    boolean cleanupPending(String id) {
        return Files.exists(root.resolve("cleanup").resolve(id + ".json"), LinkOption.NOFOLLOW_LINKS);
    }

    private Path runPath(String id) throws IOException {
        path(id);
        Path file = root.resolve("runs").resolve(id + ".json");
        DirectoryTransferPlanner.rejectLinks(file);
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
        if (run == null || run.phase() != DirectoryTransferRun.Phase.NEEDS_REVIEW
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
        DirectoryTransferPlanner.rejectLinks(file);
        return file;
    }

    record Successor(String previous, String next) {}

    private Path path(String id) throws IOException {
        try {
            if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) { throw new StorageAccessException("Invalid directory merge id."); }
        ensureRoot();
        Path path = root.resolve(id + ".json");
        DirectoryTransferPlanner.rejectLinks(path);
        return path;
    }

    private void ensureRoot() throws IOException {
        DirectoryTransferPlanner.rejectLinks(root);
        Files.createDirectories(root);
        writer.forceDirectory(root.getParent());
    }
}
