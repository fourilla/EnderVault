package io.github.fourilla.endervault.filetool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FileActionRegistryTest {

    private final FileActionRegistry registry = new FileActionRegistry();

    @Test
    void codeExtensionsUseTextToolEvenWhenMediaTypeIsGeneric() {
        FileToolDescriptor descriptor = registry.resolve(
                "Example.java",
                false,
                "application/octet-stream",
                "java"
        );

        assertThat(descriptor.type()).isEqualTo(FileToolType.TEXT);
        assertThat(descriptor.editable()).isTrue();
        assertThat(descriptor.previewable()).isFalse();
        assertThat(descriptor.previewPageAvailable()).isTrue();
    }

    @Test
    void activeWebDocumentsUseTextToolInsteadOfInlinePreview() {
        FileToolDescriptor descriptor = registry.resolve(
                "index.html",
                false,
                "text/html",
                "html"
        );

        assertThat(descriptor.type()).isEqualTo(FileToolType.TEXT);
        assertThat(descriptor.previewable()).isFalse();
        assertThat(descriptor.previewPageAvailable()).isTrue();
    }

    @Test
    void markdownFilesExposeRenderCapability() {
        FileToolDescriptor descriptor = registry.resolve(
                "README.md",
                false,
                "text/markdown",
                "md"
        );

        assertThat(descriptor.type()).isEqualTo(FileToolType.TEXT);
        assertThat(descriptor.editable()).isTrue();
        assertThat(descriptor.markdown()).isTrue();
    }

    @Test
    void comicFilesHavePreviewPageButNoInlineDetailPreview() {
        FileToolDescriptor descriptor = registry.resolve(
                "book.cbz",
                false,
                "application/octet-stream",
                "cbz"
        );

        assertThat(descriptor.type()).isEqualTo(FileToolType.COMIC);
        assertThat(descriptor.previewable()).isFalse();
        assertThat(descriptor.previewPageAvailable()).isTrue();
    }

    @Test
    void supportedArchivesExposeArchiveToolsWithoutAStandalonePreviewPage() {
        FileToolDescriptor descriptor = registry.resolve(
                "backup.tar.gz",
                false,
                "application/gzip",
                "gz"
        );

        assertThat(descriptor.type()).isEqualTo(FileToolType.ARCHIVE);
        assertThat(descriptor.archive()).isTrue();
        assertThat(descriptor.previewPageAvailable()).isFalse();
    }

    @Test
    void unknownBinaryFilesUseHexFallbackWithoutPreviewPage() {
        FileToolDescriptor descriptor = registry.resolve(
                "blob.bin",
                false,
                "application/octet-stream",
                "bin"
        );

        assertThat(descriptor.type()).isEqualTo(FileToolType.HEX);
        assertThat(descriptor.editable()).isFalse();
        assertThat(descriptor.previewPageAvailable()).isFalse();
    }

    @Test
    void browserActionsExposeDetailsForDirectories() {
        assertThat(registry.browserActions("Photos", true, "", ""))
                .containsExactly(FileActionKind.DETAILS);
    }

    @Test
    void browserActionsExposeDownloadAndPreviewForPreviewableFiles() {
        assertThat(registry.browserActions("clip.mp4", false, "video/mp4", "mp4"))
                .containsExactly(FileActionKind.DOWNLOAD, FileActionKind.PREVIEW);
    }

    @Test
    void browserActionsExposeDownloadOnlyForUnknownBinaryFiles() {
        assertThat(registry.browserActions("archive.bin", false, "application/octet-stream", "bin"))
                .containsExactly(FileActionKind.DOWNLOAD);
    }
}
