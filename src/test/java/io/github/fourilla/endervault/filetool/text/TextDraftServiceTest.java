package io.github.fourilla.endervault.filetool.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextDraftServiceTest {

    private static final String FIRST_EDITOR = "11111111-1111-1111-1111-111111111111";
    private static final String SECOND_EDITOR = "22222222-2222-2222-2222-222222222222";

    @TempDir
    Path root;

    private NasProperties properties;
    private StorageService storageService;
    private TextDraftService textDraftService;

    @BeforeEach
    void setUp() throws Exception {
        properties = new NasProperties();
        properties.getStorage().setRoot(root);
        storageService = new StorageService(properties);
        storageService.initialize();

        FileActionRegistry fileActionRegistry = new FileActionRegistry();
        TextFileService textFileService = new TextFileService(properties, fileActionRegistry);
        TextDraftRepository repository = new TextDraftRepository(
                new ObjectMapper().findAndRegisterModules(),
                properties
        );
        repository.initialize();
        textDraftService = new TextDraftService(repository, textFileService, properties);
    }

    @Test
    void autosaveCreatesRecoverableDraftWithoutChangingSource() throws Exception {
        FileDetail detail = createTextFile("note.txt", "original");
        Path source = root.resolve("note.txt");

        TextDraftStatus status = textDraftService.autosave(
                detail,
                source,
                "draft content",
                FIRST_EDITOR,
                false
        );

        assertThat(status.exists()).isTrue();
        assertThat(status.active()).isTrue();
        assertThat(status.owned()).isTrue();
        assertThat(Files.readString(source)).isEqualTo("original");
        assertThat(textDraftService.restore(detail, source, FIRST_EDITOR, false).content())
                .isEqualTo("draft content");
    }

    @Test
    void activeLeaseRejectsAnotherEditorUntilTakeover() throws Exception {
        FileDetail detail = createTextFile("note.txt", "original");
        Path source = root.resolve("note.txt");
        textDraftService.autosave(detail, source, "first", FIRST_EDITOR, false);

        assertThatThrownBy(() -> textDraftService.autosave(
                detail,
                source,
                "second",
                SECOND_EDITOR,
                false
        )).isInstanceOf(TextDraftLeaseException.class);

        TextDraftSnapshot takeover = textDraftService.restore(detail, source, SECOND_EDITOR, true);
        assertThat(takeover.content()).isEqualTo("first");
        assertThat(takeover.status().owned()).isTrue();
    }

    @Test
    void takeoverAutosaveKeepsCurrentEditorsContent() throws Exception {
        FileDetail detail = createTextFile("note.txt", "original");
        Path source = root.resolve("note.txt");
        textDraftService.autosave(detail, source, "first editor draft", FIRST_EDITOR, false);

        TextDraftStatus status = textDraftService.autosave(
                detail,
                source,
                "second editor local changes",
                SECOND_EDITOR,
                true
        );

        assertThat(status.owned()).isTrue();
        assertThat(textDraftService.restore(detail, source, SECOND_EDITOR, false).content())
                .isEqualTo("second editor local changes");
    }

    @Test
    void sourceConflictKeepsDraftUntilExplicitOverwrite() throws Exception {
        FileDetail detail = createTextFile("note.txt", "original");
        Path source = root.resolve("note.txt");
        textDraftService.autosave(detail, source, "draft content", FIRST_EDITOR, false);
        Files.writeString(source, "external change");

        assertThatThrownBy(() -> textDraftService.saveToSource(
                detail,
                source,
                "draft content",
                FIRST_EDITOR,
                false
        )).isInstanceOf(TextDraftSourceConflictException.class);
        assertThat(textDraftService.status(detail.path(), source, FIRST_EDITOR).exists()).isTrue();
        assertThat(Files.readString(source)).isEqualTo("external change");

        textDraftService.saveToSource(detail, source, "draft content", FIRST_EDITOR, true);
        assertThat(Files.readString(source)).isEqualTo("draft content");
        assertThat(textDraftService.status(detail.path(), source, FIRST_EDITOR).exists()).isFalse();
    }

    @Test
    void successfulSaveDeletesDraft() throws Exception {
        FileDetail detail = createTextFile("note.txt", "original");
        Path source = root.resolve("note.txt");
        textDraftService.autosave(detail, source, "draft content", FIRST_EDITOR, false);

        textDraftService.saveToSource(detail, source, "draft content", FIRST_EDITOR, false);

        assertThat(Files.readString(source)).isEqualTo("draft content");
        assertThat(textDraftService.status(detail.path(), source, FIRST_EDITOR).exists()).isFalse();
    }

    @Test
    void moveVaultPathRebasesDraftMetadata() throws Exception {
        Files.createDirectories(root.resolve("old"));
        FileDetail detail = createTextFile("old/note.txt", "original");
        Path source = root.resolve("old/note.txt");
        textDraftService.autosave(detail, source, "draft content", FIRST_EDITOR, false);
        Files.move(root.resolve("old"), root.resolve("new"));

        textDraftService.moveVaultPath("old", "new");

        assertThat(textDraftService.status("new/note.txt", root.resolve("new/note.txt"), FIRST_EDITOR).exists())
                .isTrue();
    }

    @Test
    void detachedAutosaveKeepsDraftAfterSourceIsDeleted() throws Exception {
        FileDetail detail = createTextFile("note.txt", "original");
        Path source = root.resolve("note.txt");
        TextDraftStatus initial = textDraftService.autosave(
                detail,
                source,
                "first draft",
                FIRST_EDITOR,
                false
        );
        Files.delete(source);

        TextDraftStatus detached = textDraftService.autosaveDetached(
                initial.id(),
                "draft after deletion",
                FIRST_EDITOR,
                false
        );

        assertThat(detached.exists()).isTrue();
        assertThat(detached.sourceMissing()).isTrue();
        assertThat(textDraftService.claimDetached(initial.id(), FIRST_EDITOR).content())
                .isEqualTo("draft after deletion");
    }

    @Test
    void draftIdentifierDetectsPathChangedByLifecycleUpdate() throws Exception {
        Files.createDirectories(root.resolve("old"));
        FileDetail detail = createTextFile("old/note.txt", "original");
        Path source = root.resolve("old/note.txt");
        TextDraftStatus initial = textDraftService.autosave(
                detail,
                source,
                "draft content",
                FIRST_EDITOR,
                false
        );

        textDraftService.moveVaultPath("old", "new");

        assertThat(textDraftService.matchesVaultPath(initial.id(), "old/note.txt")).isFalse();
        assertThat(textDraftService.matchesVaultPath(initial.id(), "new/note.txt")).isTrue();
    }

    @Test
    void recoveryCleanupDoesNotDeleteNewerDraftVersion() throws Exception {
        FileDetail detail = createTextFile("note.txt", "original");
        Path source = root.resolve("note.txt");
        TextDraftStatus initial = textDraftService.autosave(
                detail,
                source,
                "first draft",
                FIRST_EDITOR,
                false
        );
        TextDraftSnapshot claimed = textDraftService.claimDetached(initial.id(), FIRST_EDITOR);
        textDraftService.autosaveDetached(
                initial.id(),
                "newer draft",
                FIRST_EDITOR,
                false
        );

        boolean deleted = textDraftService.deleteIfUnchanged(
                initial.id(),
                FIRST_EDITOR,
                claimed.status().revision()
        );

        assertThat(deleted).isFalse();
        assertThat(textDraftService.claimDetached(initial.id(), FIRST_EDITOR).content()).isEqualTo("newer draft");
    }

    @Test
    void rejectsDraftStorageOutsideConfiguredRoot() {
        NasProperties unsafeProperties = new NasProperties();
        unsafeProperties.getStorage().setRoot(root);
        unsafeProperties.getStorage().setMetadataDirectory("../outside");

        assertThatThrownBy(() -> new TextDraftRepository(
                new ObjectMapper().findAndRegisterModules(),
                unsafeProperties
        )).isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("inside storage");
    }

    @Test
    void rejectsOversizedDraftContentModifiedOutsideTheApplication() throws Exception {
        properties.getFileTools().setTextAutoLoadMaxBytes(1024);
        properties.getFileTools().setTextManualLoadMaxBytes(1024);
        FileDetail detail = createTextFile("note.txt", "original");
        Path source = root.resolve("note.txt");
        textDraftService.autosave(detail, source, "draft content", FIRST_EDITOR, false);

        TextDraftRecord record = textDraftService.records().getFirst();
        Path draftContent = root.resolve(".endervault/drafts/text/content")
                .resolve(textDraftService.contentFileName(record.id()));
        Files.writeString(draftContent, "x".repeat(1025));

        assertThatThrownBy(() -> textDraftService.restore(detail, source, FIRST_EDITOR, false))
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("configured text limit");
    }

    private FileDetail createTextFile(String path, String content) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return storageService.detail(StorageScope.VAULT, path);
    }
}
