package io.github.fourilla.endervault.stickynote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.fourilla.endervault.bookmark.BookmarkMetadataFetcher;
import io.github.fourilla.endervault.bookmark.BookmarkService;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filerequest.FileRequest;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.filerequest.UploaderNamePolicy;
import io.github.fourilla.endervault.metadata.MetadataIssueAction;
import io.github.fourilla.endervault.metadata.StickyNoteMetadataInspector;
import io.github.fourilla.endervault.outbound.OutboundHttpClientRegistry;
import io.github.fourilla.endervault.outbound.OutboundRouteStateService;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthService;
import io.github.fourilla.endervault.outbound.vpn.VpnTunnelHealthProbe;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StickyNoteServiceTest {

    @TempDir
    Path root;

    private StickyNoteService service;
    private FileRequestService fileRequestService;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        StorageService storageService = new StorageService(properties);
        storageService.initialize();

        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
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
        fileRequestService = mock(FileRequestService.class);
        service = new StickyNoteService(
                objectMapper,
                properties,
                storageService,
                bookmarkService,
                fileRequestService,
                new StickyNotePageCatalog()
        );
        service.initialize();
    }

    @Test
    void storesNotesSeparatelyByTargetAndSurface() throws Exception {
        StickyNoteContext page = new StickyNoteContext(
                StickyNoteTargetType.PAGE,
                "dashboard",
                StickyNoteSurface.PAGE
        );
        StickyNoteContext browser = new StickyNoteContext(
                StickyNoteTargetType.STORAGE,
                "",
                StickyNoteSurface.BROWSER
        );

        StickyNote created = service.create(page, 12, 24);
        StickyNote updated = service.update(created.id(), new StickyNoteSnapshot(
                "Remember this",
                30,
                40,
                320,
                260,
                true,
                5
        ));
        service.create(browser, 1, 2);

        assertThat(service.list(page)).containsExactly(updated);
        assertThat(service.list(browser)).hasSize(1);
        assertThat(updated.content()).isEqualTo("Remember this");
        assertThat(updated.collapsed()).isTrue();
        assertThat(updated.revision()).isEqualTo(1L);
        assertThat(root.resolve(".endervault").resolve("sticky-notes.json")).exists();
    }

    @Test
    void rebasesFileAndDescendantContextsWhenDirectoryMoves() throws Exception {
        Files.createDirectories(root.resolve("docs").resolve("sub"));
        Files.writeString(root.resolve("docs").resolve("sub").resolve("note.txt"), "hello");
        StickyNote note = service.create(new StickyNoteContext(
                StickyNoteTargetType.STORAGE,
                "docs/sub/note.txt",
                StickyNoteSurface.DETAIL
        ), 0, 0);

        Files.move(root.resolve("docs"), root.resolve("renamed"));
        service.moveVaultPath("docs", "renamed");

        assertThat(service.find(note.id()).context().targetKey()).isEqualTo("renamed/sub/note.txt");
    }

    @Test
    void inspectorFindsAndRemovesMissingTargets() throws Exception {
        Files.writeString(root.resolve("note.txt"), "hello");
        StickyNote note = service.create(new StickyNoteContext(
                StickyNoteTargetType.STORAGE,
                "note.txt",
                StickyNoteSurface.DETAIL
        ), 0, 0);
        Files.delete(root.resolve("note.txt"));
        StickyNoteMetadataInspector inspector = new StickyNoteMetadataInspector(service);

        assertThat(inspector.inspect()).singleElement().satisfies(issue -> {
            assertThat(issue.subject()).isEqualTo(note.id());
            assertThat(issue.action()).isEqualTo(MetadataIssueAction.REMOVE_METADATA);
        });

        inspector.repair(MetadataIssueAction.REMOVE_METADATA, note.id());
        assertThat(service.listAll()).isEmpty();
    }

    @Test
    void opensLegacySearchNotesInTheCanonicalFileBrowser() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        StickyNote note = service.create(new StickyNoteContext(
                StickyNoteTargetType.STORAGE,
                "docs",
                StickyNoteSurface.SEARCH
        ), 0, 0);

        assertThat(service.openUrl(note.context())).isEqualTo("/files?path=docs");
    }

    @Test
    void supportsFileRequestDetailContexts() throws Exception {
        FileRequest fileRequest = new FileRequest(
                "request-1",
                "request-token",
                "Project uploads",
                "",
                "incoming",
                UploaderNamePolicy.OPTIONAL,
                1024L,
                4096L,
                4,
                List.of(),
                0L,
                0,
                List.of(),
                Instant.now(),
                null,
                true
        );
        when(fileRequestService.require("request-1")).thenReturn(fileRequest);
        StickyNoteContext context = new StickyNoteContext(
                StickyNoteTargetType.FILE_REQUEST,
                "request-1",
                StickyNoteSurface.DETAIL
        );

        StickyNote note = service.create(context, 10, 20);

        assertThat(service.list(context)).containsExactly(note);
        assertThat(service.contextLabel(context)).isEqualTo("Project uploads");
        assertThat(service.openUrl(context)).isEqualTo("/admin/file-requests/request-1");
    }
}
