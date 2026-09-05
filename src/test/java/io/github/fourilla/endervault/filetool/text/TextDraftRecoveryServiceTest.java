package io.github.fourilla.endervault.filetool.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextDraftRecoveryServiceTest {

    private static final String EDITOR = "11111111-1111-1111-1111-111111111111";

    @TempDir
    Path root;

    private StorageService storageService;
    private TextDraftService textDraftService;
    private TextDraftRecoveryService recoveryService;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        TemporaryArtifactRegistry temporaryArtifactRegistry = new TemporaryArtifactRegistry();
        storageService = new StorageService(properties, new FileActionRegistry(), temporaryArtifactRegistry);
        storageService.initialize();

        FileActionRegistry fileActionRegistry = new FileActionRegistry();
        TextFileService textFileService = new TextFileService(properties, fileActionRegistry);
        TextDraftRepository repository = new TextDraftRepository(
                JsonMapper.builder().findAndAddModules().build(),
                properties
        );
        repository.initialize();
        textDraftService = new TextDraftService(repository, textFileService, properties);
        recoveryService = new TextDraftRecoveryService(
                textDraftService,
                textFileService,
                storageService,
                temporaryArtifactRegistry
        );
    }

    @Test
    void saveAsRecoversDetachedDraftAndDeletesDraft() throws Exception {
        FileDetail detail = createTextFile("docs/note.txt", "original");
        Path source = root.resolve("docs/note.txt");
        TextDraftStatus draft = textDraftService.autosave(
                detail,
                source,
                "recovered content",
                EDITOR,
                false
        );
        Files.delete(source);

        TextDraftRecoveryResult result = recoveryService.saveAs(
                draft.id(),
                EDITOR,
                detail.path(),
                "note - recovered.txt",
                null,
                ConflictPolicy.CANCEL
        );
        FileItem saved = result.file();

        assertThat(saved.path()).isEqualTo("docs/note - recovered.txt");
        assertThat(result.originalParentMissing()).isFalse();
        assertThat(Files.readString(root.resolve(saved.path()))).isEqualTo("recovered content");
        assertThat(textDraftService.records()).isEmpty();
    }

    @Test
    void saveAsFallsBackToNearestExistingParentWhenOriginalParentIsMissing() throws Exception {
        FileDetail detail = createTextFile("existing/missing/note.txt", "original");
        Path source = root.resolve("existing/missing/note.txt");
        TextDraftStatus draft = textDraftService.autosave(
                detail,
                source,
                "recovered content",
                EDITOR,
                false
        );
        Files.delete(source);
        Files.delete(root.resolve("existing/missing"));

        TextDraftRecoveryResult result = recoveryService.saveAs(
                draft.id(),
                EDITOR,
                detail.path(),
                "note - recovered.txt",
                null,
                ConflictPolicy.CANCEL
        );
        FileItem saved = result.file();

        assertThat(saved.path()).isEqualTo("existing/note - recovered.txt");
        assertThat(result.originalParentMissing()).isTrue();
        assertThat(Files.readString(root.resolve(saved.path()))).isEqualTo("recovered content");
    }

    @Test
    void saveAsFallsBackToVaultRootWithoutCreatingRecoveryDirectory() throws Exception {
        FileDetail detail = createTextFile("missing/note.txt", "original");
        Path source = root.resolve("missing/note.txt");
        TextDraftStatus draft = textDraftService.autosave(
                detail,
                source,
                "recovered content",
                EDITOR,
                false
        );
        Files.delete(source);
        Files.delete(root.resolve("missing"));

        TextDraftRecoveryResult result = recoveryService.saveAs(
                draft.id(),
                EDITOR,
                detail.path(),
                "note - recovered.txt",
                null,
                ConflictPolicy.CANCEL
        );

        assertThat(result.file().path()).isEqualTo("note - recovered.txt");
        assertThat(result.originalParentMissing()).isTrue();
        assertThat(root.resolve("Recovered")).doesNotExist();
    }

    @Test
    void conflictCancelKeepsDraftForAnotherRecoveryAttempt() throws Exception {
        FileDetail detail = createTextFile("docs/note.txt", "original");
        Path source = root.resolve("docs/note.txt");
        TextDraftStatus draft = textDraftService.autosave(
                detail,
                source,
                "draft content",
                EDITOR,
                false
        );
        Files.delete(source);
        Files.writeString(root.resolve("docs/note - recovered.txt"), "existing");

        assertThatThrownBy(() -> recoveryService.saveAs(
                draft.id(),
                EDITOR,
                detail.path(),
                "note - recovered.txt",
                null,
                ConflictPolicy.CANCEL
        )).isInstanceOf(FileAlreadyExistsException.class);

        assertThat(textDraftService.records()).hasSize(1);
        assertThat(textDraftService.claimDetached(draft.id(), EDITOR).content()).isEqualTo("draft content");
    }

    @Test
    void saveAsUsesBrowserContentWhenSourceDisappearsBeforeFirstDraft() throws Exception {
        Files.createDirectories(root.resolve("docs"));

        TextDraftRecoveryResult result = recoveryService.saveAs(
                null,
                EDITOR,
                "docs/note.txt",
                "note - recovered.txt",
                "browser-only content",
                ConflictPolicy.CANCEL
        );
        FileItem saved = result.file();

        assertThat(saved.path()).isEqualTo("docs/note - recovered.txt");
        assertThat(result.originalParentMissing()).isFalse();
        assertThat(Files.readString(root.resolve(saved.path()))).isEqualTo("browser-only content");
    }

    private FileDetail createTextFile(String path, String content) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return storageService.detail(StorageScope.VAULT, path);
    }
}
