package io.github.fourilla.endervault.trash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.fourilla.endervault.bookmark.BookmarkMetadataFetcher;
import io.github.fourilla.endervault.bookmark.BookmarkService;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.outbound.OutboundHttpClientRegistry;
import io.github.fourilla.endervault.outbound.OutboundRouteStateService;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthService;
import io.github.fourilla.endervault.outbound.vpn.VpnTunnelHealthProbe;
import io.github.fourilla.endervault.recent.RecentService;
import io.github.fourilla.endervault.share.ShareLink;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrashServiceTest {

    @TempDir
    Path root;

    private StorageService storageService;
    private ShareLinkService shareLinkService;
    private FavoriteService favoriteService;
    private RecentService recentService;
    private TrashRepository trashRepository;
    private TrashService trashService;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        storageService = new StorageService(properties);
        storageService.initialize();

        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        shareLinkService = new ShareLinkService(
                storageService,
                objectMapper,
                properties,
                new PublicLinkTokenService()
        );
        shareLinkService.initialize();
        BookmarkService bookmarkService = new BookmarkService(
                objectMapper,
                properties,
                new BookmarkMetadataFetcher(
                        properties,
                        new OutboundHttpClientRegistry(
                                new VpnProxyHealthService(properties, new VpnTunnelHealthProbe())
                        )
                ),
                new OutboundRouteStateService(properties),
                new TemporaryArtifactRegistry()
        );
        bookmarkService.initialize();
        favoriteService = new FavoriteService(storageService, bookmarkService, objectMapper, properties);
        favoriteService.initialize();
        recentService = new RecentService(storageService, objectMapper, properties);
        recentService.initialize();
        trashRepository = new TrashRepository(objectMapper, properties);
        trashRepository.initialize();
        trashService = new TrashService(
                storageService,
                trashRepository,
                shareLinkService,
                favoriteService,
                recentService,
                properties
        );
    }

    @Test
    void movesFileToTrashWithMetadataAndRevokesShareLinksFavoritesAndRecent() throws Exception {
        Files.writeString(root.resolve("note.txt"), "hello");
        ShareLink shareLink = shareLinkService.create("", "note.txt", null);
        favoriteService.toggle("note.txt");
        recentService.recordVaultPath("note.txt");

        List<TrashRecord> records = trashService.moveToTrash("", List.of("note.txt"));

        TrashRecord record = records.get(0);
        assertThat(root.resolve("note.txt")).doesNotExist();
        assertThat(root.resolve(".trash").resolve(record.trashName())).exists();
        assertThat(root.resolve(".endervault").resolve("trash-records.json")).exists();
        assertThat(record.originalPath()).isEqualTo("note.txt");
        assertThat(record.originalName()).isEqualTo("note.txt");
        assertThat(record.directory()).isFalse();
        assertThat(trashService.list()).extracting(TrashRecord::id).containsExactly(record.id());
        assertThatThrownBy(() -> shareLinkService.requireUsable(shareLink.token()))
                .isInstanceOf(NoSuchFileException.class);
        assertThat(favoriteService.list()).isEmpty();
        assertThat(recentService.storedItems()).isEmpty();
    }

    @Test
    void restoresTrashItemToOriginalPath() throws Exception {
        Files.writeString(root.resolve("note.txt"), "hello");
        TrashRecord record = trashService.moveToTrash("", List.of("note.txt")).get(0);

        trashService.restore(record.id());

        assertThat(Files.readString(root.resolve("note.txt"))).isEqualTo("hello");
        assertThat(root.resolve(".trash").resolve(record.trashName())).doesNotExist();
        assertThat(trashService.list()).isEmpty();
    }

    @Test
    void restoreFailsWhenOriginalPathAlreadyExists() throws Exception {
        Files.writeString(root.resolve("note.txt"), "hello");
        TrashRecord record = trashService.moveToTrash("", List.of("note.txt")).get(0);
        Files.writeString(root.resolve("note.txt"), "replacement");

        assertThatThrownBy(() -> trashService.restore(record.id()))
                .isInstanceOf(FileAlreadyExistsException.class);

        assertThat(Files.readString(root.resolve("note.txt"))).isEqualTo("replacement");
        assertThat(root.resolve(".trash").resolve(record.trashName())).exists();
        assertThat(trashService.list()).extracting(TrashRecord::id).containsExactly(record.id());
    }

    @Test
    void permanentlyDeletesTrashRecordAndFile() throws Exception {
        Files.writeString(root.resolve("note.txt"), "hello");
        TrashRecord record = trashService.moveToTrash("", List.of("note.txt")).get(0);

        trashService.deletePermanently(record.id());

        assertThat(root.resolve(".trash").resolve(record.trashName())).doesNotExist();
        assertThat(trashService.list()).isEmpty();
    }

    @Test
    void cleanupExpiredPermanentlyDeletesOldEntries() throws Exception {
        Files.writeString(root.resolve(".trash").resolve("expired"), "old");
        TrashRecord record = new TrashRecord(
                "expired",
                "old.txt",
                "",
                "old.txt",
                "expired",
                false,
                3L,
                "3 B",
                "Text",
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-02T00:00:00Z")
        );
        trashRepository.add(record);

        int deletedCount = trashService.cleanupExpired();

        assertThat(deletedCount).isEqualTo(1);
        assertThat(root.resolve(".trash").resolve("expired")).doesNotExist();
        assertThat(trashService.list()).isEmpty();
    }

    @Test
    void emptyDeletesKnownRecordsAndUnknownTrashFiles() throws Exception {
        Files.writeString(root.resolve("note.txt"), "hello");
        TrashRecord record = trashService.moveToTrash("", List.of("note.txt")).get(0);
        Files.writeString(root.resolve(".trash").resolve("orphan"), "orphan");

        int deletedCount = trashService.empty();

        assertThat(deletedCount).isEqualTo(1);
        assertThat(root.resolve(".trash").resolve(record.trashName())).doesNotExist();
        assertThat(root.resolve(".trash").resolve("orphan")).doesNotExist();
        assertThat(trashService.list()).isEmpty();
    }
}
