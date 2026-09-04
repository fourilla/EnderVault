package io.github.fourilla.endervault.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
import io.github.fourilla.endervault.storage.StorageService;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShareLinkServiceTest {

    @TempDir
    Path root;

    private NasProperties properties;
    private StorageService storageService;
    private ObjectMapper objectMapper;
    private ShareLinkService shareLinkService;

    @BeforeEach
    void setUp() throws Exception {
        properties = new NasProperties();
        properties.getStorage().setRoot(root);
        storageService = new StorageService(properties);
        storageService.initialize();

        objectMapper = JsonMapper.builder().findAndAddModules().build();
        shareLinkService = new ShareLinkService(storageService, objectMapper, properties, new PublicLinkTokenService());
        shareLinkService.initialize();
    }

    @Test
    void createsFileShareLinkInMetadataRegistry() throws Exception {
        Files.writeString(root.resolve("note.txt"), "hello");

        ShareLink shareLink = shareLinkService.create("", "note.txt", null);

        assertThat(shareLink.token()).isNotBlank();
        assertThat(shareLink.path()).isEqualTo("note.txt");
        assertThat(shareLink.type()).isEqualTo(ShareTargetType.FILE);
        assertThat(shareLink.previewEnabled()).isTrue();
        assertThat(shareLinkService.requireUsable(shareLink.token())).isEqualTo(shareLink);
        assertThat(root.resolve(".endervault").resolve("shared-links.json")).exists();
    }

    @Test
    void usesTheCurrentDefaultOnlyWhenCreatingANewLink() throws Exception {
        Files.writeString(root.resolve("default.txt"), "default");
        Files.writeString(root.resolve("override.txt"), "override");
        properties.getShare().setDefaultPreviewEnabled(false);

        ShareLink defaultLink = shareLinkService.create("", "default.txt", null);
        ShareLink explicitLink = shareLinkService.create("", "override.txt", null, null, true);

        assertThat(defaultLink.previewEnabled()).isFalse();
        assertThat(explicitLink.previewEnabled()).isTrue();

        properties.getShare().setDefaultPreviewEnabled(true);
        assertThat(shareLinkService.list())
                .filteredOn(link -> link.token().equals(defaultLink.token()))
                .singleElement()
                .extracting(ShareLink::previewEnabled)
                .isEqualTo(false);
    }

    @Test
    void treatsLegacyRecordsWithoutPreviewPolicyAsDisabled() throws Exception {
        Path registry = root.resolve(".endervault").resolve("shared-links.json");
        Files.writeString(registry, """
                [{
                  "token": "legacy-share-token",
                  "path": "legacy.txt",
                  "type": "FILE",
                  "createdAt": "2026-01-01T00:00:00Z",
                  "expiresAt": null,
                  "enabled": true
                }]
                """);
        ShareLinkService reloaded = new ShareLinkService(
                storageService, objectMapper, properties, new PublicLinkTokenService());
        reloaded.initialize();

        assertThat(reloaded.list()).singleElement()
                .extracting(ShareLink::previewEnabled)
                .isEqualTo(false);
    }

    @Test
    void createsDirectoryShareLink() throws Exception {
        Files.createDirectories(root.resolve("docs"));

        ShareLink shareLink = shareLinkService.create("", "docs", null);

        assertThat(shareLink.path()).isEqualTo("docs");
        assertThat(shareLink.type()).isEqualTo(ShareTargetType.DIRECTORY);
    }

    @Test
    void createsShareLinkWithCustomToken() throws Exception {
        Files.writeString(root.resolve("note.txt"), "hello");

        ShareLink shareLink = shareLinkService.create("", "note.txt", null, "demo_token_123");

        assertThat(shareLink.token()).isEqualTo("demo_token_123");
        assertThat(shareLinkService.requireUsable("demo_token_123")).isEqualTo(shareLink);
    }

    @Test
    void rejectsDuplicateCustomShareToken() throws Exception {
        Files.writeString(root.resolve("a.txt"), "a");
        Files.writeString(root.resolve("b.txt"), "b");
        shareLinkService.create("", "a.txt", null, "duplicate-share-token");

        assertThatThrownBy(() -> shareLinkService.create("", "b.txt", null, "duplicate-share-token"))
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void rejectsInvalidCustomShareToken() throws Exception {
        Files.writeString(root.resolve("note.txt"), "hello");

        assertThatThrownBy(() -> shareLinkService.create("", "note.txt", null, "bad/token"))
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("Share token");
    }

    @Test
    void listsShareLinksForVaultPath() throws Exception {
        Files.writeString(root.resolve("note.txt"), "hello");
        ShareLink shareLink = shareLinkService.createForVaultPath("note.txt", null);

        assertThat(shareLinkService.listForVaultPath("note.txt")).containsExactly(shareLink);
    }

    @Test
    void rejectsRootShareLinkFromVaultPath() {
        assertThatThrownBy(() -> shareLinkService.createForVaultPath("", null))
                .isInstanceOf(StorageAccessException.class);
    }

    @Test
    void rejectsExpiredShareLink() throws Exception {
        Files.writeString(root.resolve("note.txt"), "hello");
        ShareLink shareLink = shareLinkService.create("", "note.txt", Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> shareLinkService.requireUsable(shareLink.token()))
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void revokesShareLink() throws Exception {
        Files.writeString(root.resolve("note.txt"), "hello");
        ShareLink shareLink = shareLinkService.create("", "note.txt", null);

        shareLinkService.revoke(shareLink.token());

        assertThatThrownBy(() -> shareLinkService.requireUsable(shareLink.token()))
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void movesShareLinksForPathAndDescendants() throws Exception {
        Files.createDirectories(root.resolve("docs").resolve("sub"));
        Files.writeString(root.resolve("docs").resolve("sub").resolve("note.txt"), "hello");
        ShareLink directoryShare = shareLinkService.create("", "docs", null);
        ShareLink fileShare = shareLinkService.createForVaultPath("docs/sub/note.txt", null);

        Files.move(root.resolve("docs"), root.resolve("renamed"));
        shareLinkService.moveVaultPath("docs", "renamed");

        assertThat(shareLinkService.requireUsable(directoryShare.token()).path()).isEqualTo("renamed");
        assertThat(shareLinkService.requireUsable(fileShare.token()).path()).isEqualTo("renamed/sub/note.txt");
    }

    @Test
    void revokesShareLinksForPathAndDescendants() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs").resolve("note.txt"), "hello");
        ShareLink directoryShare = shareLinkService.create("", "docs", null);
        ShareLink fileShare = shareLinkService.createForVaultPath("docs/note.txt", null);

        shareLinkService.revokeVaultPath("docs");

        assertThatThrownBy(() -> shareLinkService.requireUsable(directoryShare.token()))
                .isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> shareLinkService.requireUsable(fileShare.token()))
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void deletesShareLinkRecord() throws Exception {
        Files.writeString(root.resolve("note.txt"), "hello");
        ShareLink shareLink = shareLinkService.create("", "note.txt", null);

        shareLinkService.delete(shareLink.token());

        assertThat(shareLinkService.list()).isEmpty();
        assertThatThrownBy(() -> shareLinkService.requireUsable(shareLink.token()))
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void deletesExpiredShareLinksOnly() throws Exception {
        Files.writeString(root.resolve("old.txt"), "old");
        Files.writeString(root.resolve("new.txt"), "new");
        ShareLink expired = shareLinkService.create("", "old.txt", Instant.parse("2024-01-01T00:00:00Z"));
        ShareLink active = shareLinkService.create("", "new.txt", Instant.parse("2099-01-01T00:00:00Z"));

        int deletedCount = shareLinkService.deleteExpired(Instant.parse("2025-01-01T00:00:00Z"));

        assertThat(deletedCount).isEqualTo(1);
        assertThat(shareLinkService.list()).extracting(ShareLink::token).containsExactly(active.token());
        assertThatThrownBy(() -> shareLinkService.requireUsable(expired.token()))
                .isInstanceOf(NoSuchFileException.class);
    }
}
