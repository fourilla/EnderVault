package io.github.fourilla.endervault.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.bookmark.BookmarkService;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.favorite.FavoriteItem;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.filerequest.FileRequestRepository;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.filerequest.UploaderNamePolicy;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.filetool.text.TextDraftRepository;
import io.github.fourilla.endervault.filetool.text.TextDraftService;
import io.github.fourilla.endervault.filetool.text.TextFileService;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
import io.github.fourilla.endervault.recent.RecentItem;
import io.github.fourilla.endervault.recent.RecentService;
import io.github.fourilla.endervault.share.ShareLink;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.stickynote.StickyNoteContext;
import io.github.fourilla.endervault.stickynote.StickyNotePageCatalog;
import io.github.fourilla.endervault.stickynote.StickyNoteService;
import io.github.fourilla.endervault.stickynote.StickyNoteSurface;
import io.github.fourilla.endervault.stickynote.StickyNoteTargetType;
import io.github.fourilla.endervault.thumbnail.ThumbnailService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Exercises the real six metadata registries as a whole-directory move owner would replay them. */
class FileLifecycleMoveMetadataTest {
    private static final String OLD = "from/photos";
    private static final String NEW = "to/photos";
    private static final String CHILD = "/sub/a.txt";
    private static final String EDITOR = "11111111-1111-1111-1111-111111111111";
    @TempDir Path root;
    StorageService storage;
    ShareLinkService shares;
    FavoriteService favorites;
    RecentService recent;
    TextDraftService drafts;
    StickyNoteService notes;
    FileRequestService requests;
    ThumbnailService thumbnails;
    ActivityLogService activity;
    FileLifecycleService lifecycle;

    @BeforeEach void setup() throws Exception {
        var properties = new NasProperties();
        properties.getStorage().setRoot(root);
        properties.getRecent().setRecordDirectories(true);
        var mapper = JsonMapper.builder().findAndAddModules().build();
        storage = new StorageService(properties);
        storage.initialize();
        shares = new ShareLinkService(storage, mapper, properties, new PublicLinkTokenService());
        shares.initialize();
        var bookmarks = mock(BookmarkService.class);
        favorites = new FavoriteService(storage, bookmarks, mapper, properties);
        favorites.initialize();
        recent = new RecentService(storage, mapper, properties);
        recent.initialize();
        var draftRepository = new TextDraftRepository(mapper, properties);
        draftRepository.initialize();
        drafts = new TextDraftService(draftRepository,
                new TextFileService(properties, new FileActionRegistry()), properties);
        var requestRepository = new FileRequestRepository(mapper, properties);
        requestRepository.initialize();
        requests = spy(new FileRequestService(requestRepository, storage, new PublicLinkTokenService(), properties));
        notes = new StickyNoteService(mapper, properties, storage, bookmarks, requests, new StickyNotePageCatalog());
        notes.initialize();
        thumbnails = mock(ThumbnailService.class);
        activity = mock(ActivityLogService.class);
        lifecycle = new FileLifecycleService(storage, shares, favorites, recent, thumbnails, drafts, notes, requests, activity);
        file(OLD + CHILD, "original");
        file(OLD + "-other/sub/a.txt", "different tree");
        Files.createDirectories(root.resolve("to"));
        seed(OLD);
        seed(OLD + "-other");
        Files.move(root.resolve(OLD), root.resolve(NEW));
    }

    void file(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    void seed(String path) throws Exception {
        shares.createForVaultPath(path, null);
        shares.createForVaultPath(path + CHILD, null);
        favorites.toggle(path);
        favorites.toggle(path + CHILD);
        recent.recordVaultPath(path);
        recent.recordVaultPath(path + CHILD);
        drafts.autosave(storage.detail(StorageScope.VAULT, path + CHILD), root.resolve(path + CHILD),
                "draft", EDITOR, false);
        notes.create(new StickyNoteContext(StickyNoteTargetType.STORAGE, path, StickyNoteSurface.BROWSER), 0, 0);
        notes.create(new StickyNoteContext(StickyNoteTargetType.STORAGE, path + CHILD, StickyNoteSurface.DETAIL), 0, 0);
        requests.create("Root", "", path, UploaderNamePolicy.OPTIONAL, 1024, 4096, 3, List.of(), 7, null);
        requests.create("Child", "", path + "/sub", UploaderNamePolicy.OPTIONAL, 1024, 4096, 3, List.of(), 7, null);
    }

    void assertRebasedRegistries() throws Exception {
        var expected = List.of(NEW, NEW + CHILD, OLD + "-other", OLD + "-other" + CHILD);
        assertThat(shares.list()).extracting(ShareLink::path).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(favorites.list()).extracting(FavoriteItem::path).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(recent.storedItems()).extracting(RecentItem::path).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(drafts.records()).extracting(record -> record.vaultPath())
                .containsExactlyInAnyOrder(NEW + CHILD, OLD + "-other" + CHILD);
        assertThat(notes.listAll()).extracting(note -> note.context().targetKey())
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(requests.list()).extracting(request -> request.destinationPath())
                .containsExactlyInAnyOrder(NEW, NEW + "/sub", OLD + "-other", OLD + "-other/sub");
    }

    @Test void rootMetadataReplayRebasesEveryRegistryAndPreservesIdsAndUnrelatedPrefixes() throws Exception {
        var shareIds = shares.list().stream().map(ShareLink::token).toList();
        var draftIds = drafts.records().stream().map(record -> record.id()).toList();
        var noteIds = notes.listAll().stream().map(note -> note.id()).toList();
        var requestIds = requests.list().stream().map(request -> request.id()).toList();
        lifecycle.applyMovedPathMetadata(OLD, NEW);
        assertRebasedRegistries();
        assertThat(shares.list()).extracting(ShareLink::token).containsExactlyInAnyOrderElementsOf(shareIds);
        assertThat(drafts.records()).extracting(record -> record.id()).containsExactlyInAnyOrderElementsOf(draftIds);
        assertThat(notes.listAll()).extracting(note -> note.id()).containsExactlyInAnyOrderElementsOf(noteIds);
        assertThat(requests.list()).extracting(request -> request.id()).containsExactlyInAnyOrderElementsOf(requestIds);
        var sharesAfter = shares.list();
        var favoritesAfter = favorites.list();
        var recentAfter = recent.storedItems();
        var draftsAfter = drafts.records();
        var notesAfter = notes.listAll();
        var requestsAfter = requests.list();
        lifecycle.applyMovedPathMetadata(OLD, NEW);
        assertThat(shares.list()).isEqualTo(sharesAfter);
        assertThat(favorites.list()).isEqualTo(favoritesAfter);
        assertThat(recent.storedItems()).isEqualTo(recentAfter);
        assertThat(drafts.records()).isEqualTo(draftsAfter);
        assertThat(notes.listAll()).isEqualTo(notesAfter);
        assertThat(requests.list()).isEqualTo(requestsAfter);
        verify(thumbnails, times(2)).migrateThumbnails(root.resolve(NEW), OLD, NEW);
        verifyNoInteractions(activity);
    }

    @Test void failureInLastRegistryCanReplayWithoutIncrementingAlreadyMovedDraftsAndNotesAgain() throws Exception {
        doThrow(new IOException("last registry unavailable")).doCallRealMethod().when(requests).moveVaultPath(OLD, NEW);
        assertThatThrownBy(() -> lifecycle.applyMovedPathMetadata(OLD, NEW))
                .isInstanceOf(IOException.class).hasMessage("last registry unavailable");
        var draftsAfterPartial = drafts.records();
        var notesAfterPartial = notes.listAll();
        assertThat(requests.list()).extracting(request -> request.destinationPath()).contains(OLD, OLD + "/sub");
        lifecycle.applyMovedPathMetadata(OLD, NEW);
        assertRebasedRegistries();
        assertThat(drafts.records()).isEqualTo(draftsAfterPartial);
        assertThat(notes.listAll()).isEqualTo(notesAfterPartial);
        assertThat(root.resolve(NEW + CHILD)).hasContent("original");
        assertThat(root.resolve(OLD + "-other" + CHILD)).hasContent("different tree");
        verifyNoInteractions(activity);
    }
}
