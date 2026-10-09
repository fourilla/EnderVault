package io.github.fourilla.endervault.directorytransfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filecommit.FileCommitJournalStore;
import io.github.fourilla.endervault.storage.StorageService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class DirectoryTransferMoveRecordsTest {
    @TempDir Path vault;
    final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    NasProperties properties;
    StorageService storage;
    DirectoryTransferReviewStore store;
    DirectoryTransferReview review;
    DirectoryTransferRecordCleanup cleanup;
    DirectoryTransferMetadataInspector inspector;

    @BeforeEach void setup() throws Exception {
        properties = new NasProperties();
        properties.getStorage().setRoot(vault);
        storage = new StorageService(properties);
        storage.initialize();
        Files.createDirectories(vault.resolve("from/photos/sub"));
        Files.writeString(vault.resolve("from/photos/sub/한글 + [1].txt"), "content");
        Files.createDirectories(vault.resolve("to"));
        store = new DirectoryTransferReviewStore(mapper, properties);
        review = store.create(new DirectoryTransferPlanner(storage, properties)
                .planTransfer(DirectoryTransferPlan.Operation.MOVE, "from/photos", "to", null));
        cleanup = new DirectoryTransferRecordCleanup(store, mapper);
        inspector = new DirectoryTransferMetadataInspector(store, mapper, mock(FileCommitJournalStore.class),
                mock(DirectoryTransferPendingInspector.class));
    }

    String id() { return review.plan().id(); }

    DirectoryTransferRun start() throws Exception {
        store.freeze(id(), review.revision());
        var run = new DirectoryTransferRun(id(), review.revision(), DirectoryTransferRun.Phase.PUBLISHING, false,
                DirectoryTransferRun.Strategy.WHOLE_DIRECTORY_MOVE);
        store.saveRun(null, run);
        return run;
    }

    DirectoryTransferMoveRecord intent() throws Exception {
        return new DirectoryTransferMoveRecord(id(), review.revision(), review.plan().sourceReference(), review.plan().destinationPath(),
                DirectoryTransferPlanner.snapshotIfPresent(vault.resolve("from")),
                DirectoryTransferPlanner.snapshotIfPresent(vault.resolve("to")),
                DirectoryTransferMoveRecord.fingerprint(review.plan()), DirectoryTransferMoveRecord.Phase.INTENT, null);
    }

    DirectoryTransferMoveRecord phase(DirectoryTransferMoveRecord record, DirectoryTransferMoveRecord.Phase phase) {
        return new DirectoryTransferMoveRecord(record.id(), record.revision(), record.sourcePath(), record.targetPath(),
                record.sourceParent(), record.targetParent(), record.treeFingerprint(), phase, null, record.entryKeys());
    }

    DirectoryTransferMoveRecord metadataProof() throws Exception {
        start();
        var record = intent();
        store.saveMove(null, record);
        var renamed = phase(record, DirectoryTransferMoveRecord.Phase.RENAMED);
        store.saveMove(record, renamed);
        var applied = phase(renamed, DirectoryTransferMoveRecord.Phase.METADATA_APPLIED);
        store.saveMove(renamed, applied);
        return applied;
    }

    DirectoryTransferResult moved(DirectoryTransferPlan.Item item) {
        return new DirectoryTransferResult(item.id(), DirectoryTransferResult.Status.MOVED,
                new DirectoryTransferIndex(review.plan()).plannedTargetPath(item), item.source(), null, null, id());
    }

    void ledger() throws Exception {
        for (var item : review.plan().items()) {
            store.recordResult(review, item, moved(item));
            store.recordMovedCompletion(review, item);
        }
    }

    void completeRun() throws Exception {
        var run = store.run(id());
        var finalizing = new DirectoryTransferRun(id(), review.revision(), DirectoryTransferRun.Phase.FINALIZING, false, run.strategy());
        store.saveRun(run, finalizing);
        store.saveRun(finalizing, new DirectoryTransferRun(id(), review.revision(), DirectoryTransferRun.Phase.COMPLETE, false, run.strategy()));
    }

    @Test void legacyRunAndResultRemainReadableWithoutSelectingRename() throws Exception {
        var run = mapper.readValue("""
                {"id":"%s","revision":0,"phase":"PUBLISHING","paused":true}
                """.formatted(id()), DirectoryTransferRun.class);
        assertThat(run.strategy()).isEqualTo(DirectoryTransferRun.Strategy.ITEMWISE);
        assertThat(run.paused()).isTrue();
        var file = review.plan().items().stream().filter(item -> item.source().kind() == DirectoryTransferPlan.Kind.FILE).findFirst().orElseThrow();
        var old = new DirectoryTransferResult(file.id(), DirectoryTransferResult.Status.PUBLISHED, "to/photos/file.txt", file.source(), UUID.randomUUID().toString(), null);
        var json = mapper.writeValueAsString(old).replace(",\"renameId\":null", "");
        assertThat(mapper.readValue(json, DirectoryTransferResult.class)).isEqualTo(old);
        assertThatThrownBy(() -> new DirectoryTransferResult(file.id(), DirectoryTransferResult.Status.PUBLISHED,
                "to/photos/file.txt", file.source(), null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void fingerprintIgnoresItemOrderAndIdsButTracksAllSourceSnapshots() {
        var items = new ArrayList<>(review.plan().items());
        Collections.reverse(items);
        var reordered = new DirectoryTransferPlan(UUID.randomUUID().toString(), review.plan().operation(), review.plan().sourceReference(),
                review.plan().destinationPath(), review.plan().createdAt(), items);
        assertThat(DirectoryTransferMoveRecord.fingerprint(reordered)).isEqualTo(DirectoryTransferMoveRecord.fingerprint(review.plan()));
        var file = items.stream().filter(item -> item.source().kind() == DirectoryTransferPlan.Kind.FILE).findFirst().orElseThrow();
        var changed = new DirectoryTransferPlan.Snapshot(file.source().kind(), file.source().size() + 1,
                file.source().modifiedAt(), file.source().fileKey(), file.source().createdAt());
        items.set(items.indexOf(file), new DirectoryTransferPlan.Item(file.id(), file.relativePath(), changed, null, file.conflict(), null));
        var altered = new DirectoryTransferPlan(reordered.id(), reordered.operation(), reordered.sourceReference(), reordered.destinationPath(), reordered.createdAt(), items);
        assertThat(DirectoryTransferMoveRecord.fingerprint(altered)).isNotEqualTo(DirectoryTransferMoveRecord.fingerprint(review.plan()));
    }

    @Test void durableRunStrategyCannotChangeAndPauseFailurePreserveIt() throws Exception {
        var run = start();
        assertThatThrownBy(() -> store.saveRun(run, new DirectoryTransferRun(id(), 0, run.phase(), false)))
                .hasMessageContaining("Invalid merge run transition");
        store.recordFailure(run, new IOException("retry metadata"));
        var failed = store.run(id());
        assertThat(failed.recoveryRequired()).isTrue();
        store.pauseRun(failed, new IllegalStateException("cancel"));
        var paused = store.run(id());
        assertThat(paused.strategy()).isEqualTo(DirectoryTransferRun.Strategy.WHOLE_DIRECTORY_MOVE);
        assertThat(paused.recoveryRequired()).isTrue();
        assertThat(paused.paused()).isTrue();
    }

    @Test void moveNeedsFrozenWholeApprovalAndBoundPathsRevisionFingerprint() throws Exception {
        assertThatThrownBy(() -> store.saveMove(null, intent())).hasMessageContaining("frozen approval");
        start();
        var intent = intent();
        assertThatThrownBy(() -> store.saveMove(null, new DirectoryTransferMoveRecord(id(), 1, intent.sourcePath(), intent.targetPath(),
                intent.sourceParent(), intent.targetParent(), intent.treeFingerprint(), intent.phase(), null)))
                .hasMessageContaining("approved tree");
        assertThatThrownBy(() -> store.saveMove(null, new DirectoryTransferMoveRecord(id(), 0, intent.sourcePath(), "elsewhere/photos",
                intent.sourceParent(), intent.targetParent(), intent.treeFingerprint(), intent.phase(), null)))
                .hasMessageContaining("approved tree");
        assertThatThrownBy(() -> store.saveMove(null, new DirectoryTransferMoveRecord(id(), 0, intent.sourcePath(), intent.targetPath(),
                intent.sourceParent(), intent.targetParent(), "0".repeat(64), intent.phase(), null)))
                .hasMessageContaining("approved tree");
        store.saveMove(null, intent);
        assertThat(store.move(id())).isEqualTo(intent);
        assertThat(inspector.inspect()).isEmpty();
    }

    @Test void moveIntentIsImmutableAndPhasesCannotSkipOrGoBackwards() throws Exception {
        start();
        var intent = intent();
        store.saveMove(null, intent);
        store.saveMove(intent, intent);
        assertThatThrownBy(() -> store.saveMove(intent, phase(intent, DirectoryTransferMoveRecord.Phase.COMPLETE)))
                .hasMessageContaining("transition");
        var changedParent = new DirectoryTransferPlan.Snapshot(intent.sourceParent().kind(), intent.sourceParent().size(),
                intent.sourceParent().modifiedAt(), "replacement", intent.sourceParent().createdAt());
        assertThatThrownBy(() -> store.saveMove(intent, new DirectoryTransferMoveRecord(id(), 0, intent.sourcePath(), intent.targetPath(),
                changedParent, intent.targetParent(), intent.treeFingerprint(), DirectoryTransferMoveRecord.Phase.RENAMED, null)))
                .hasMessageContaining("transition");
        var renamed = phase(intent, DirectoryTransferMoveRecord.Phase.RENAMED);
        store.saveMove(intent, renamed);
        assertThatThrownBy(() -> store.saveMove(renamed, intent)).hasMessageContaining("transition");
    }

    @Test void moveEntryIdentitiesBindEveryApprovedRelativePathAndCannotChangeAfterIntent() throws Exception {
        start();
        var legacy = intent();
        var keys = new HashMap<String, String>();
        for (var item : review.plan().items()) keys.put(item.relativePath(), "stable:" + item.id());
        var complete = new DirectoryTransferMoveRecord(legacy.id(), legacy.revision(), legacy.sourcePath(), legacy.targetPath(),
                legacy.sourceParent(), legacy.targetParent(), legacy.treeFingerprint(), legacy.phase(), legacy.detail(), keys);
        var incomplete = new DirectoryTransferMoveRecord(legacy.id(), legacy.revision(), legacy.sourcePath(), legacy.targetPath(),
                legacy.sourceParent(), legacy.targetParent(), legacy.treeFingerprint(), legacy.phase(), legacy.detail(), Map.of("", "root"));
        assertThatThrownBy(() -> store.saveMove(null, incomplete)).hasMessageContaining("approved tree");
        store.saveMove(null, complete);
        assertThat(store.move(id()).entryKeys()).isEqualTo(keys);
        assertThat(mapper.readValue(mapper.writeValueAsString(complete), DirectoryTransferMoveRecord.class)).isEqualTo(complete);
        keys.put("", "replacement");
        var changed = new DirectoryTransferMoveRecord(complete.id(), complete.revision(), complete.sourcePath(), complete.targetPath(),
                complete.sourceParent(), complete.targetParent(), complete.treeFingerprint(), DirectoryTransferMoveRecord.Phase.RENAMED, null, keys);
        assertThatThrownBy(() -> store.saveMove(complete, changed)).hasMessageContaining("transition");
        var renamed = phase(complete, DirectoryTransferMoveRecord.Phase.RENAMED);
        store.saveMove(complete, renamed);
        var applied = phase(renamed, DirectoryTransferMoveRecord.Phase.METADATA_APPLIED);
        store.saveMove(renamed, applied);
        var source = review.plan().items().getFirst().source();
        var unknown = new DirectoryTransferPlan.Item(UUID.randomUUID().toString(), "unapproved", source, null,
                DirectoryTransferPlan.Conflict.ADD, null);
        var result = new DirectoryTransferResult(unknown.id(), DirectoryTransferResult.Status.MOVED,
                "to/photos/unapproved", source, null, null, id());
        assertThatThrownBy(() -> DirectoryTransferReviewStore.validateResultProof(review, unknown, result, applied))
                .hasMessageContaining("proof");
        var approved = review.plan().items().getFirst();
        store.recordResult(review, approved, moved(approved));
        store.recordMovedCompletion(review, approved);
        assertThat(inspector.inspect()).isEmpty();
    }

    @Test void oldMoveRecordWithoutNativeIdentitySetRemainsReadableButInvalidIdentityEntriesAreRejected() throws Exception {
        var old = intent();
        var json = mapper.writeValueAsString(old).replace(",\"entryKeys\":{}", "");
        assertThat(mapper.readValue(json, DirectoryTransferMoveRecord.class)).isEqualTo(old);
        assertThatThrownBy(() -> new DirectoryTransferMoveRecord(old.id(), old.revision(), old.sourcePath(), old.targetPath(),
                old.sourceParent(), old.targetParent(), old.treeFingerprint(), old.phase(), null, Map.of("../outside", "key")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DirectoryTransferMoveRecord(old.id(), old.revision(), old.sourcePath(), old.targetPath(),
                old.sourceParent(), old.targetParent(), old.treeFingerprint(), old.phase(), null, Map.of("", " ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void itemProofOverloadsRejectStaleUndurableEvidenceAndCachedReadsInvalidateOnFileChange() throws Exception {
        var applied = metadataProof();
        var item = review.plan().items().getFirst();
        var undurable = new DirectoryTransferMoveRecord(applied.id(), applied.revision(), applied.sourcePath(), applied.targetPath(),
                applied.sourceParent(), applied.targetParent(), applied.treeFingerprint(), applied.phase(), "not recorded", applied.entryKeys());
        assertThatThrownBy(() -> store.recordResult(review, item, moved(item), undurable)).hasMessageContaining("item proof changed");
        store.recordResult(review, item, moved(item), applied);
        var file = store.inspectionRoot().resolve("moves").resolve(id() + ".json");
        Files.write(file, mapper.writeValueAsBytes(phase(applied, DirectoryTransferMoveRecord.Phase.NEEDS_REVIEW)));
        assertThatThrownBy(() -> store.recordMovedCompletion(review, item, applied)).hasMessageContaining("item proof changed");
        assertThatThrownBy(() -> store.recordMovedCompletion(review, item)).hasMessageContaining("metadata proof");
        assertThatThrownBy(() -> store.recordResult(review, item, moved(item))).hasMessageContaining("metadata has not completed");
        assertThatThrownBy(() -> store.saveMove(applied, phase(applied, DirectoryTransferMoveRecord.Phase.COMPLETE)))
                .hasMessageContaining("evidence changed");
    }

    @Test void onlyDurableNoEffectFallbackCanEnterTheExistingPublicationAndSourceCleanupPipeline() throws Exception {
        store.requireItemwiseExecution(id());
        start();
        assertThatThrownBy(() -> store.requireItemwiseExecution(id())).hasMessageContaining("cannot enter itemwise");
        var intent = intent();
        store.saveMove(null, intent);
        assertThatThrownBy(() -> store.requireItemwiseExecution(id())).hasMessageContaining("cannot enter itemwise");
        store.saveMove(intent, phase(intent, DirectoryTransferMoveRecord.Phase.FALLBACK));
        store.requireItemwiseExecution(id());
    }

    @Test void preMoveReviewStopMayHaveNoIntentButCannotAlreadyContainItemOutcomes() throws Exception {
        var run = start();
        store.saveRun(run, new DirectoryTransferRun(id(), 0, DirectoryTransferRun.Phase.NEEDS_REVIEW, false, run.strategy()));
        assertThat(inspector.inspect()).isEmpty();
        var summary = new DirectoryTransferQueryService(store).get(id(), 0, 50, false).review();
        assertThat(summary.canReplan()).isFalse();
        assertThat(summary.canAbandonRemainingTransfer()).isFalse();
        assertThat(cleanup.eligible(id())).isFalse();
        var item = review.plan().items().getFirst();
        var file = store.inspectionRoot().resolve("results").resolve(id()).resolve(item.id() + ".json");
        Files.createDirectories(file.getParent());
        Files.write(file, mapper.writeValueAsBytes(new DirectoryTransferResult(item.id(), DirectoryTransferResult.Status.PUBLISHED,
                "to/photos", item.source(), null, null)));
        assertThat(inspector.inspect()).anyMatch(issue -> issue.title().contains("outcomes before move evidence"));
    }

    @Test void movedOutcomeAndDirectCompletionRequireMetadataProofAndNoJournal() throws Exception {
        start();
        var intent = intent();
        store.saveMove(null, intent);
        var item = review.plan().items().getFirst();
        assertThatThrownBy(() -> store.recordResult(review, item, moved(item))).hasMessageContaining("proof");
        assertThatThrownBy(() -> store.recordMovedCompletion(review, item)).hasMessageContaining("metadata proof");
        assertThatThrownBy(() -> new DirectoryTransferResult(item.id(), DirectoryTransferResult.Status.MOVED,
                "to/photos", item.source(), UUID.randomUUID().toString(), null, id())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DirectoryTransferResult(item.id(), DirectoryTransferResult.Status.MOVED,
                "to/photos", item.source(), null, null)).isInstanceOf(IllegalArgumentException.class);
        var renamed = phase(intent, DirectoryTransferMoveRecord.Phase.RENAMED);
        store.saveMove(intent, renamed);
        var applied = phase(renamed, DirectoryTransferMoveRecord.Phase.METADATA_APPLIED);
        store.saveMove(renamed, applied);
        assertThatThrownBy(() -> store.recordMovedCompletion(review, item)).hasMessageContaining("result");
        store.recordResult(review, item, moved(item));
        store.recordMovedCompletion(review, item);
        store.recordMovedCompletion(review, item);
        assertThat(store.completion(id(), item.id()).phase()).isEqualTo(DirectoryTransferCompletion.Phase.COMPLETE);
        assertThat(vault.resolve("from/photos/sub/한글 + [1].txt")).exists();
    }

    @Test void wrongRenameReferenceSnapshotOrTargetCannotCreateAnOutcome() throws Exception {
        metadataProof();
        var item = review.plan().items().getFirst();
        assertThatThrownBy(() -> store.recordResult(review, item, new DirectoryTransferResult(item.id(), DirectoryTransferResult.Status.MOVED,
                "to/photos", item.source(), null, null, UUID.randomUUID().toString()))).hasMessageContaining("proof");
        assertThatThrownBy(() -> store.recordResult(review, item, new DirectoryTransferResult(item.id(), DirectoryTransferResult.Status.MOVED,
                "to/other", item.source(), null, null, id()))).hasMessageContaining("proof");
        var changed = new DirectoryTransferPlan.Snapshot(item.source().kind(), item.source().size(), item.source().modifiedAt(), "other", item.source().createdAt());
        assertThatThrownBy(() -> store.recordResult(review, item, new DirectoryTransferResult(item.id(), DirectoryTransferResult.Status.MOVED,
                "to/photos", changed, null, null, id()))).hasMessageContaining("proof");
    }

    @Test void completedMoveUsesExistingQueryOutcomesAndCleanupRetiresOnlyRecords() throws Exception {
        var applied = metadataProof();
        ledger();
        store.saveMove(applied, phase(applied, DirectoryTransferMoveRecord.Phase.COMPLETE));
        completeRun();
        var query = new DirectoryTransferQueryService(store);
        assertThat(query.get(id(), 0, 50, false, true).entries().items()).isEmpty();
        assertThat(query.get(id(), 0, 50, false).review().canReplan()).isFalse();
        assertThat(inspector.inspect()).isEmpty();
        assertThat(cleanup.eligible(id())).isTrue();
        cleanup.approve(id());
        cleanup.finish(id());
        assertThat(store.inspectionRoot().resolve("moves").resolve(id() + ".json")).doesNotExist();
        assertThat(store.inspectionRoot().resolve("runs").resolve(id() + ".json")).doesNotExist();
        assertThat(vault.resolve("from/photos/sub/한글 + [1].txt")).exists();
        assertThat(inspector.inspect()).isEmpty();
    }

    @Test void partialHistoricalOutcomesStayReadableAfterMoveRequiresReviewButCannotAdvanceOrClean() throws Exception {
        var applied = metadataProof();
        var item = review.plan().items().getFirst();
        store.recordResult(review, item, moved(item));
        store.recordMovedCompletion(review, item);
        store.saveMove(applied, phase(applied, DirectoryTransferMoveRecord.Phase.NEEDS_REVIEW));
        var run = store.run(id());
        store.saveRun(run, new DirectoryTransferRun(id(), 0, DirectoryTransferRun.Phase.NEEDS_REVIEW, false, run.strategy()));
        var query = new DirectoryTransferQueryService(store);
        assertThat(query.get(id(), 0, 50, false, true).entries().total()).isEqualTo(review.plan().items().size() - 1);
        assertThat(query.get(id(), 0, 50, false).review().canReplan()).isFalse();
        assertThat(query.get(id(), 0, 50, false).review().canAbandonRemainingTransfer()).isFalse();
        assertThat(cleanup.eligible(id())).isFalse();
        assertThatThrownBy(() -> store.recordResult(review, item, moved(item))).hasMessageContaining("metadata has not completed");
        assertThatThrownBy(() -> store.recordMovedCompletion(review, item)).hasMessageContaining("metadata proof");
        assertThatThrownBy(() -> new DirectoryTransferReplanningService(store, new DirectoryTransferPlanner(storage, properties), storage)
                .replan(id(), 0, null)).hasMessageContaining("manual review");
    }

    @Test void ordinaryNeedsReviewRetainsReplanningCapability() throws Exception {
        store.freeze(id(), 0);
        var ordinary = new DirectoryTransferRun(id(), 0, DirectoryTransferRun.Phase.PUBLISHING, false);
        store.saveRun(null, ordinary);
        var finalizing = new DirectoryTransferRun(id(), 0, DirectoryTransferRun.Phase.FINALIZING, false);
        store.saveRun(ordinary, finalizing);
        store.saveRun(finalizing, new DirectoryTransferRun(id(), 0, DirectoryTransferRun.Phase.NEEDS_REVIEW, false));
        assertThat(new DirectoryTransferQueryService(store).get(id(), 0, 50, false).review().canReplan()).isTrue();
    }

    @Test void unfinishedOrCorruptMoveProofIsRetainedAndReportedWithoutBackupWrites() throws Exception {
        var applied = metadataProof();
        ledger();
        completeRun();
        assertThat(cleanup.eligible(id())).isFalse();
        assertThat(inspector.inspect()).anyMatch(issue -> issue.title().contains("unsettled move evidence"));
        var file = store.inspectionRoot().resolve("moves").resolve(id() + ".json");
        Files.writeString(file, "{broken");
        assertThat(inspector.inspect()).anyMatch(issue -> issue.title().contains("unreadable"));
        assertThat(Files.readString(file)).isEqualTo("{broken");
        try (var paths = Files.list(file.getParent())) { assertThat(paths.toList()).containsExactly(file); }
    }

    @Test void publicationOutcomesCannotMixWithRenameButConfirmedFallbackAllowsExistingRecords() throws Exception {
        start();
        var intent = intent();
        store.saveMove(null, intent);
        var root = review.plan().items().getFirst();
        var published = new DirectoryTransferResult(root.id(), DirectoryTransferResult.Status.PUBLISHED, "to/photos", root.source(), null, null);
        assertThatThrownBy(() -> store.recordResult(review, root, published)).hasMessageContaining("publication results");
        store.saveMove(intent, phase(intent, DirectoryTransferMoveRecord.Phase.FALLBACK));
        for (var item : review.plan().items()) {
            var target = new DirectoryTransferIndex(review.plan()).plannedTargetPath(item);
            var journal = item.source().kind() == DirectoryTransferPlan.Kind.FILE ? UUID.randomUUID().toString() : null;
            store.recordResult(review, item, new DirectoryTransferResult(item.id(), DirectoryTransferResult.Status.PUBLISHED, target, item.source(), journal, null));
            store.recordCompletion(id(), null, new DirectoryTransferCompletion(item.id(), DirectoryTransferCompletion.Phase.PREPARED, null));
            store.recordCompletion(id(), store.completion(id(), item.id()), new DirectoryTransferCompletion(item.id(), DirectoryTransferCompletion.Phase.METADATA_APPLIED, null));
            store.recordCompletion(id(), store.completion(id(), item.id()), new DirectoryTransferCompletion(item.id(), DirectoryTransferCompletion.Phase.COMPLETE, null));
        }
        completeRun();
        assertThat(cleanup.eligible(id())).isTrue();
        assertThat(inspector.inspect()).isEmpty();
    }

    @Test void moveSidecarCannotAttachToAnItemwiseOrUnrelatedApproval() throws Exception {
        store.freeze(id(), 0);
        store.saveRun(null, new DirectoryTransferRun(id(), 0, DirectoryTransferRun.Phase.PUBLISHING, false));
        var file = store.inspectionRoot().resolve("moves").resolve(id() + ".json");
        Files.createDirectories(file.getParent());
        Files.write(file, mapper.writeValueAsBytes(intent()));
        assertThat(inspector.inspect()).anyMatch(issue -> issue.title().contains("unreadable or inconsistent"));
        assertThat(cleanup.eligible(id())).isFalse();
    }
}
