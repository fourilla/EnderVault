package io.github.fourilla.endervault.filetool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.text.TextFileService;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileToolServiceTest {

    @TempDir
    Path root;

    private NasProperties properties;
    private StorageService storageService;
    private FileToolService fileToolService;
    private TextFileService textFileService;

    @BeforeEach
    void setUp() throws Exception {
        properties = new NasProperties();
        properties.getStorage().setRoot(root);
        storageService = new StorageService(properties);
        storageService.initialize();
        fileToolService = new FileToolService(new FileActionRegistry());
        textFileService = new TextFileService(properties, new FileActionRegistry());
    }

    @Test
    void resolvesTxtFilesAsEditableTextTool() throws Exception {
        Files.writeString(root.resolve("note.txt"), "hello");
        FileDetail detail = storageService.detail(StorageScope.VAULT, "note.txt");

        FileToolDescriptor descriptor = fileToolService.resolve(detail);
        TextFileContent content = textFileService.readText(detail, root.resolve("note.txt"));

        assertThat(descriptor.type()).isEqualTo(FileToolType.TEXT);
        assertThat(descriptor.editable()).isTrue();
        assertThat(descriptor.previewable()).isFalse();
        assertThat(content.loaded()).isTrue();
        assertThat(content.content()).isEqualTo("hello");
    }

    @Test
    void resolvesCodeAndMarkdownFilesAsEditableTextTools() throws Exception {
        Files.writeString(root.resolve("README.md"), "# hello");
        Files.writeString(root.resolve("script.js"), "console.log('hello');");
        Files.writeString(root.resolve("Dockerfile"), "FROM eclipse-temurin:21");

        assertThat(fileToolService.resolve(storageService.detail(StorageScope.VAULT, "README.md")).type())
                .isEqualTo(FileToolType.TEXT);
        assertThat(fileToolService.resolve(storageService.detail(StorageScope.VAULT, "script.js")).type())
                .isEqualTo(FileToolType.TEXT);
        assertThat(fileToolService.resolve(storageService.detail(StorageScope.VAULT, "Dockerfile")).type())
                .isEqualTo(FileToolType.TEXT);
    }

    @Test
    void requiresManualLoadingTextFilesAboveAutoLimit() throws Exception {
        properties.getFileTools().setTextAutoLoadMaxBytes(1024);
        properties.getFileTools().setTextManualLoadMaxBytes(2048);
        Files.writeString(root.resolve("large.txt"), "x".repeat(1025));
        FileDetail detail = storageService.detail(StorageScope.VAULT, "large.txt");

        TextFileContent content = textFileService.readText(detail, root.resolve("large.txt"));

        assertThat(content.loaded()).isFalse();
        assertThat(content.editable()).isFalse();
        assertThat(content.manualLoadAvailable()).isTrue();
        assertThat(content.message()).contains("Load it manually");
    }

    @Test
    void manuallyLoadsTextFilesWithinManualLimit() throws Exception {
        properties.getFileTools().setTextAutoLoadMaxBytes(1024);
        properties.getFileTools().setTextManualLoadMaxBytes(2048);
        Files.writeString(root.resolve("large.txt"), "x".repeat(1025));
        FileDetail detail = storageService.detail(StorageScope.VAULT, "large.txt");

        TextFileContent content = textFileService.loadText(detail, root.resolve("large.txt"));

        assertThat(content.loaded()).isTrue();
        assertThat(content.editable()).isTrue();
        assertThat(content.content()).hasSize(1025);
    }

    @Test
    void rejectsManualLoadingTextFilesAboveManualLimit() throws Exception {
        properties.getFileTools().setTextAutoLoadMaxBytes(1024);
        properties.getFileTools().setTextManualLoadMaxBytes(2048);
        Files.writeString(root.resolve("huge.txt"), "x".repeat(2049));
        FileDetail detail = storageService.detail(StorageScope.VAULT, "huge.txt");

        TextFileContent content = textFileService.readText(detail, root.resolve("huge.txt"));

        assertThat(content.loaded()).isFalse();
        assertThat(content.editable()).isFalse();
        assertThat(content.manualLoadAvailable()).isFalse();
        assertThat(content.message()).contains("larger than");
        assertThatThrownBy(() -> textFileService.loadText(detail, root.resolve("huge.txt")))
                .isInstanceOf(StorageAccessException.class);
    }

    @Test
    void rejectsSavingTextAboveManualLimit() throws Exception {
        properties.getFileTools().setTextAutoLoadMaxBytes(1024);
        properties.getFileTools().setTextManualLoadMaxBytes(1024);
        Files.writeString(root.resolve("note.txt"), "hello");
        FileDetail detail = storageService.detail(StorageScope.VAULT, "note.txt");

        assertThatThrownBy(() -> textFileService.writeText(detail, root.resolve("note.txt"), "x".repeat(1025)))
                .isInstanceOf(StorageAccessException.class);
    }

    @Test
    void unknownExtensionsUseHexFallback() throws Exception {
        Files.write(root.resolve("blob.bin"), new byte[] {1, 2, 3});
        FileDetail detail = storageService.detail(StorageScope.VAULT, "blob.bin");

        FileToolDescriptor descriptor = fileToolService.resolve(detail);

        assertThat(descriptor.type()).isEqualTo(FileToolType.HEX);
        assertThat(descriptor.editable()).isFalse();
        assertThat(descriptor.previewable()).isFalse();
    }

    @Test
    void resolvesCbzFilesAsComicTool() throws Exception {
        Files.write(root.resolve("comic.cbz"), new byte[] {1, 2, 3});
        FileDetail detail = storageService.detail(StorageScope.VAULT, "comic.cbz");

        FileToolDescriptor descriptor = fileToolService.resolve(detail);

        assertThat(descriptor.type()).isEqualTo(FileToolType.COMIC);
        assertThat(descriptor.editable()).isFalse();
        assertThat(descriptor.previewable()).isFalse();
    }
}
