package io.github.fourilla.endervault.web.api.v1.stickynote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import io.github.fourilla.endervault.search.SearchQueryException;
import io.github.fourilla.endervault.stickynote.StickyNote;
import io.github.fourilla.endervault.stickynote.StickyNoteContext;
import io.github.fourilla.endervault.stickynote.StickyNoteService;
import io.github.fourilla.endervault.stickynote.StickyNoteSurface;
import io.github.fourilla.endervault.stickynote.StickyNoteTargetType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class StickyNoteCatalogServiceTest {

    private final StickyNoteService notes = mock(StickyNoteService.class);
    private final StickyNoteCatalogService catalog = new StickyNoteCatalogService(notes);

    @Test
    void preservesDefaultSummarySearchAndAllowsExplicitFullContentSearch() throws Exception {
        StickyNote note = note("visible summary " + "x".repeat(120) + " hiddenword");
        when(notes.listAll()).thenReturn(List.of(note));
        when(notes.contextLabel(note.context())).thenReturn("Dashboard");

        assertThat(catalog.load("hiddenword").notes()).isEmpty();
        assertThat(catalog.load("content:hiddenword").notes()).hasSize(1);
        assertThat(catalog.load("name:hiddenword").notes()).isEmpty();
        assertThat(catalog.load("visible dashboard page").notes()).hasSize(1);
    }

    @Test
    void typedDatesAndKindsKeepOrphansAndDoNotChangeCatalogOrderOrLabels() throws Exception {
        StickyNote first = note("summer holiday");
        StickyNote second = new StickyNote("second", first.context(), "holiday summer", 0, 0, null,
                280, 220, false, 1, 0, first.createdAt(), null);
        when(notes.listAll()).thenReturn(List.of(first, second));
        when(notes.contextLabel(first.context())).thenReturn("Missing target");
        when(notes.targetExists(any())).thenReturn(false);

        assertThat(catalog.load("summer holiday target:missing type:PAGE surface:PAGE").notes())
                .extracting(StickyNoteCatalogPayload.StickyNoteCatalogItemPayload::id)
                .containsExactly(first.id(), second.id());
        var item = catalog.load("\"summer holiday\" created:2024-01-01T00:00:00Z updated:>=2024-01-02T00:00:00Z")
                .notes().getFirst();
        assertThat(item.targetType()).isEqualTo("PAGE");
        assertThat(item.surfaceLabel()).isEqualTo("Page");
        assertThat(item.targetExists()).isFalse();
        assertThat(item.openUrl()).isNull();
    }

    @Test
    void filtersBeforeTargetExistenceChecksAndValidatesBeforeReadingNotes() throws Exception {
        assertThatThrownBy(() -> catalog.load("  unknown:value"))
                .isInstanceOf(SearchQueryException.class).hasMessage("Unknown search field: unknown");
        assertThatThrownBy(() -> catalog.load(" ".repeat(4097)))
                .isInstanceOf(SearchQueryException.class).hasMessage("Search query is too long.");
        assertThatThrownBy(() -> catalog.load("surface:invalid"))
                .isInstanceOf(SearchQueryException.class);
        verifyNoInteractions(notes);

        StickyNote note = note("not matched");
        when(notes.listAll()).thenReturn(List.of(note));
        when(notes.contextLabel(note.context())).thenReturn("Dashboard");
        assertThat(catalog.load("name:excluded").notes()).isEmpty();
        verify(notes, never()).targetExists(any());
        verify(notes, never()).openUrl(any());
    }

    @Test
    void statusReusesTargetChecksAcrossConditionsPayloadAndNotesOnlyWithinOneRequest() throws Exception {
        StickyNote first = note("summer holiday");
        StickyNote second = new StickyNote("second", first.context(), "another note", 0, 0, null,
                280, 220, false, 1, 0, first.createdAt(), first.updatedAt());
        var missingContext = new StickyNoteContext(StickyNoteTargetType.STORAGE, "missing.txt", StickyNoteSurface.DETAIL);
        StickyNote orphan = new StickyNote("orphan", missingContext, "summer missing", 0, 0, null,
                280, 220, false, 1, 0, first.createdAt(), first.updatedAt());
        when(notes.listAll()).thenReturn(List.of(first, second, orphan));
        when(notes.contextLabel(first.context())).thenReturn("Dashboard");
        when(notes.contextLabel(missingContext)).thenReturn("Missing target");
        when(notes.targetExists(first.context())).thenReturn(true);
        when(notes.openUrl(first.context())).thenReturn("/admin");
        when(notes.targetExists(missingContext)).thenReturn(false);

        assertThat(catalog.load("status:AVAILABLE status:available").notes())
                .extracting(StickyNoteCatalogPayload.StickyNoteCatalogItemPayload::id).containsExactly("first", "second");
        verify(notes, times(1)).targetExists(first.context());
        verify(notes, times(1)).targetExists(missingContext);
        assertThat(catalog.load("status:orphan").notes()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo("orphan");
            assertThat(item.targetExists()).isFalse();
            assertThat(item.openUrl()).isNull();
        });
        verify(notes, times(2)).targetExists(first.context());
        verify(notes, times(2)).targetExists(missingContext);
        verify(notes, never()).openUrl(missingContext);
    }

    @Test
    void excludedNotesAndInvalidStatusNeverCauseTargetReadsOrFallback() throws Exception {
        assertThatThrownBy(() -> catalog.load("name:summer || status:invalid"))
                .isInstanceOf(SearchQueryException.class).hasMessage("Invalid value for search field: status");
        verifyNoInteractions(notes);
        StickyNote note = note("summer");
        when(notes.listAll()).thenReturn(List.of(note));
        when(notes.contextLabel(note.context())).thenReturn("Dashboard");
        assertThat(catalog.load("name:missing status:orphan").notes()).isEmpty();
        verify(notes, never()).targetExists(any());
        verify(notes, never()).openUrl(any());
    }

    private StickyNote note(String content) {
        return new StickyNote("first", new StickyNoteContext(StickyNoteTargetType.PAGE, "dashboard", StickyNoteSurface.PAGE),
                content, 0, 0, null, 280, 220, false, 1, 0,
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-02T00:00:00Z"));
    }
}
