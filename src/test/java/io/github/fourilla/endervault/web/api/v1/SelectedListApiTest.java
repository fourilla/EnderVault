package io.github.fourilla.endervault.web.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.stickynote.StickyNote;
import io.github.fourilla.endervault.stickynote.StickyNoteContext;
import io.github.fourilla.endervault.stickynote.StickyNoteService;
import io.github.fourilla.endervault.stickynote.StickyNoteSurface;
import io.github.fourilla.endervault.stickynote.StickyNoteTargetType;
import io.github.fourilla.endervault.trash.TrashRecord;
import io.github.fourilla.endervault.trash.TrashService;
import io.github.fourilla.endervault.web.api.v1.favorite.FavoriteApiController;
import io.github.fourilla.endervault.web.api.v1.stickynote.StickyNoteApiController;
import io.github.fourilla.endervault.web.api.v1.stickynote.StickyNoteCatalogService;
import io.github.fourilla.endervault.web.api.v1.trash.TrashApiController;
import io.github.fourilla.endervault.web.support.SelectedItemActions;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SelectedListApiTest {
    private final TrashService trash = mock(TrashService.class);
    private final FavoriteService favorites = mock(FavoriteService.class);
    private final StickyNoteService notes = mock(StickyNoteService.class);
    private final ActivityLogService activity = mock(ActivityLogService.class);
    private final TrashApiController trashController = new TrashApiController(trash, activity);
    private final FavoriteApiController favoriteController = new FavoriteApiController(favorites, new NasProperties());
    private final StickyNoteApiController noteController = new StickyNoteApiController(notes, activity, mock(StickyNoteCatalogService.class));
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();

    @Test
    void invalidSelectionsAndUnsupportedActionsNeverBeginMutations() {
        var invalid = Arrays.<List<String>>asList(null, List.of(), List.of(first, first), Arrays.asList(first, null),
                IntStream.range(0, 201).mapToObj(i -> UUID.randomUUID().toString()).toList());
        for (var ids : invalid) {
            assertThat(trashController.resolveSelected(ids, "DELETE", true, request).getStatusCode().value()).isEqualTo(400);
            assertThat(noteController.resolveSelected(ids, "DELETE", true, request).getStatusCode().value()).isEqualTo(400);
            assertThat(resolveFavorites(ids, "REMOVE", true).getStatusCode().value()).isEqualTo(400);
        }
        for (var ids : List.of(List.of(first, "../bad"), List.of(first, "1-1-1-1-1"))) {
            assertThat(trashController.resolveSelected(ids, "DELETE", true, request).getStatusCode().value()).isEqualTo(400);
            assertThat(noteController.resolveSelected(ids, "DELETE", true, request).getStatusCode().value()).isEqualTo(400);
        }
        assertThat(resolveFavorites(List.of("folder", "\0"), "REMOVE", true).getStatusCode().value()).isEqualTo(400);
        assertThat(resolveFavorites(List.of("folder", " "), "REMOVE", true).getStatusCode().value()).isEqualTo(400);
        assertThat(trashController.resolveSelected(List.of(first), "RESTORE", true, request).getStatusCode().value()).isEqualTo(400);
        assertThat(noteController.resolveSelected(List.of(first), "DELETE", false, request).getStatusCode().value()).isEqualTo(400);
        assertThat(resolveFavorites(List.of("folder"), "DELETE", true).getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(trash, notes, favorites, activity);
    }

    @Test
    void trashBatchReusesPermanentDeletionAndPerItemActivity() throws Exception {
        TrashRecord record = mock(TrashRecord.class);
        when(record.originalPath()).thenReturn("photos/image.png");
        when(trash.deletePermanently(first)).thenReturn(record);
        when(trash.deletePermanently(second)).thenThrow(new IOException("private location"));
        var body = (SelectedItemActions.Response) trashController.resolveSelected(List.of(first, second), "DELETE", true, request).getBody();
        assertThat(body.succeededCount()).isEqualTo(1);
        assertThat(body.failedCount()).isEqualTo(1);
        assertThat(body.results().get(1).message()).doesNotContain("private");
        var order = inOrder(trash);
        order.verify(trash).deletePermanently(first); order.verify(trash).deletePermanently(second);
        verify(trash, never()).restore(anyString());
        verify(activity).record(eq("TRASH_DELETE"), same(request), eq("photos/image.png"), isNull(), anyString());
        verifyNoMoreInteractions(activity);
    }

    @Test
    void noteBatchReusesDeletionLogsAndContinuesAfterARejectedItem() throws Exception {
        StickyNote note = mock(StickyNote.class);
        when(note.id()).thenReturn(second);
        when(note.context()).thenReturn(new StickyNoteContext(StickyNoteTargetType.PAGE, "dashboard", StickyNoteSurface.PAGE));
        when(notes.delete(first)).thenThrow(new StorageAccessException("private metadata"));
        when(notes.delete(second)).thenReturn(note);
        var body = (SelectedItemActions.Response) noteController.resolveSelected(List.of(first, second), "DELETE", true, request).getBody();
        assertThat(body.results()).extracting(SelectedItemActions.ItemResult::status)
                .containsExactly(SelectedItemActions.Status.REJECTED, SelectedItemActions.Status.APPLIED);
        verify(activity).record(eq("STICKY_NOTE_DELETE"), same(request), anyString(), eq("dashboard"), anyString(),
                eq(java.util.Map.of("noteId", second, "targetType", "PAGE", "surface", "PAGE")));
        verifyNoMoreInteractions(activity);
    }

    @Test
    void favoriteBatchPreservesExactRegistryIdentitiesWithoutTouchingTargets() throws Exception {
        var keys = List.of("한글/file.txt", "bookmark:" + first, "Case", "case");
        var body = (SelectedItemActions.Response) resolveFavorites(keys, "REMOVE", true).getBody();
        assertThat(body.results()).extracting(SelectedItemActions.ItemResult::id).containsExactlyElementsOf(keys);
        assertThat(body.succeededCount()).isEqualTo(4);
        var order = inOrder(favorites);
        for (String key : keys) order.verify(favorites).remove(key);
        verifyNoMoreInteractions(favorites);
        verifyNoInteractions(trash, notes, activity);
    }

    @Test
    void encodedMvcBindingAcceptsRepeatedKeysAndRejectsBadArgumentsWithoutRedirect() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(trashController, favoriteController, noteController).build();
        mvc.perform(post("/api/v1/favorites/selected/resolve").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("ids", "folder", "bookmark:" + first).param("action", "REMOVE").param("confirmed", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.succeededCount").value(2)).andExpect(jsonPath("$.redirectUrl").doesNotExist());
        for (String path : List.of("trash", "sticky-notes", "favorites")) {
            String url = "/api/v1/" + path + "/selected/resolve";
            mvc.perform(post(url).param("ids", first).param("action", "UNKNOWN").param("confirmed", "true"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post(url).param("action", "DELETE").param("confirmed", "true"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post(url).param("ids", first).param("action", "DELETE").param("confirmed", "not-boolean"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(trash, notes, activity);
    }

    @Test
    void oneEncodedFavoriteKeyContainingCommaIsNotSplitIntoSeveralItems() throws Exception {
        MockMvcBuilders.standaloneSetup(favoriteController).build()
                .perform(post("/api/v1/favorites/selected/resolve").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("ids", "photos/a,b.txt").param("action", "REMOVE").param("confirmed", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.succeededCount").value(1))
                .andExpect(jsonPath("$.results[0].id").value("photos/a,b.txt"));
        verify(favorites).remove("photos/a,b.txt");
        verifyNoMoreInteractions(favorites);
    }

    private ResponseEntity<?> resolveFavorites(List<String> ids, String action, boolean confirmed) {
        var parameters = new LinkedMultiValueMap<String, String>();
        if (ids != null) parameters.put("ids", ids);
        return favoriteController.resolveSelected(parameters, action, confirmed);
    }
}
