package io.github.fourilla.endervault.filecommit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCommitJournalStoreTest {

    @TempDir
    Path tempDirectory;

    private ObjectMapper objectMapper;
    private Path journalRoot;
    private FileCommitJournalStore store;

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        journalRoot = tempDirectory.resolve("commit-journal");
        store = new FileCommitJournalStore(objectMapper, journalRoot);
        store.initialize();
    }

    @Test
    void persistsAndReloadsAnOperationAcrossStoreInstances() throws IOException {
        FileCommitManifest manifest = manifest();

        FileCommitJournalEntry created = store.create(manifest);
        FileCommitJournalState committing = new FileCommitJournalState(
                manifest.operationId(),
                FileCommitPhase.COMMITTING,
                0,
                "Moving staged output.",
                Instant.now()
        );
        store.updateState(committing);

        FileCommitJournalStore restarted = new FileCommitJournalStore(objectMapper, journalRoot);
        restarted.initialize();
        FileCommitJournalEntry restored = restarted.load(manifest.operationId());

        assertThat(created.state().phase()).isEqualTo(FileCommitPhase.PREPARED);
        assertThat(restored.manifest()).isEqualTo(manifest);
        assertThat(restored.state().phase()).isEqualTo(FileCommitPhase.COMMITTING);
        assertThat(restored.state().detail()).isEqualTo("Moving staged output.");
    }

    @Test
    void requiresCompletedStateBeforeDeletingJournal() throws IOException {
        FileCommitManifest manifest = manifest();
        store.create(manifest);

        assertThatThrownBy(() -> store.deleteCompleted(manifest.operationId()))
                .isInstanceOf(IllegalStateException.class);

        advanceToCompleted(manifest.operationId());
        store.deleteCompleted(manifest.operationId());

        assertThat(store.list()).isEmpty();
        assertThat(Files.exists(journalRoot.resolve(manifest.operationId()))).isFalse();
    }

    @Test
    void rejectsInvalidTransitionsAndProgressBeyondPlan() throws IOException {
        FileCommitManifest manifest = manifest();
        store.create(manifest);

        assertThatThrownBy(() -> store.updateState(new FileCommitJournalState(
                manifest.operationId(), FileCommitPhase.FILES_MOVED, 1, null, Instant.now()
        ))).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> store.updateState(new FileCommitJournalState(
                manifest.operationId(), FileCommitPhase.COMMITTING, 2, null, Instant.now()
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonUuidOperationIdsAndEscapingItemPaths() {
        assertThatThrownBy(() -> new FileCommitManifest(
                FileCommitManifest.CURRENT_SCHEMA_VERSION,
                "../escape",
                new FileCommitOwner(FileCommitOwnerType.RESUMABLE_UPLOAD, "session"),
                FileCommitOperationType.SINGLE_FILE,
                ConflictPolicy.CANCEL,
                List.of(item()),
                Instant.now()
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new FileCommitItem(
                0,
                "../outside.tmp",
                "target.txt",
                new FileCommitFingerprint(3L, Instant.now(), null),
                null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotSilentlyResetCorruptJournalData() throws IOException {
        FileCommitManifest manifest = manifest();
        store.create(manifest);
        Files.writeString(journalRoot.resolve(manifest.operationId()).resolve("state.json"), "{broken");

        assertThatThrownBy(() -> store.load(manifest.operationId()))
                .isInstanceOf(IOException.class);
        assertThat(Files.readString(journalRoot.resolve(manifest.operationId()).resolve("state.json")))
                .isEqualTo("{broken");
    }

    private void advanceToCompleted(String operationId) throws IOException {
        store.updateState(state(operationId, FileCommitPhase.COMMITTING, 0));
        store.updateState(state(operationId, FileCommitPhase.FILES_MOVED, 1));
        store.updateState(state(operationId, FileCommitPhase.APPLYING_METADATA, 1));
        store.updateState(state(operationId, FileCommitPhase.COMPLETED, 1));
    }

    private FileCommitJournalState state(String operationId, FileCommitPhase phase, int nextItemIndex) {
        return new FileCommitJournalState(operationId, phase, nextItemIndex, null, Instant.now());
    }

    private FileCommitManifest manifest() {
        return new FileCommitManifest(
                FileCommitManifest.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                new FileCommitOwner(FileCommitOwnerType.RESUMABLE_UPLOAD, "session-1"),
                FileCommitOperationType.SINGLE_FILE,
                ConflictPolicy.CANCEL,
                List.of(item()),
                Instant.now()
        );
    }

    private FileCommitItem item() {
        return new FileCommitItem(
                0,
                ".endervault/file-staging/resumable.tmp",
                "incoming/upload.txt",
                new FileCommitFingerprint(3L, Instant.now(), "file-key"),
                null
        );
    }
}
