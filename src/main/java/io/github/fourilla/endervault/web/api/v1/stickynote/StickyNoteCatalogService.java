package io.github.fourilla.endervault.web.api.v1.stickynote;

import io.github.fourilla.endervault.stickynote.StickyNote;
import io.github.fourilla.endervault.stickynote.StickyNoteService;
import io.github.fourilla.endervault.web.api.v1.stickynote.StickyNoteCatalogPayload.StickyNoteCatalogItemPayload;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class StickyNoteCatalogService {

    private static final DateTimeFormatter UPDATED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final StickyNoteService stickyNoteService;

    public StickyNoteCatalogService(StickyNoteService stickyNoteService) {
        this.stickyNoteService = stickyNoteService;
    }

    public StickyNoteCatalogPayload load(String query) throws IOException {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return new StickyNoteCatalogPayload(stickyNoteService.listAll().stream()
                .map(this::item)
                .filter(item -> normalizedQuery.isBlank() || matches(item, normalizedQuery))
                .toList());
    }

    private StickyNoteCatalogItemPayload item(StickyNote note) {
        boolean targetExists = stickyNoteService.targetExists(note.context());
        return new StickyNoteCatalogItemPayload(
                note.id(),
                note.content(),
                note.summary(),
                stickyNoteService.contextLabel(note.context()),
                note.context().targetType().name(),
                note.context().surface().label(),
                note.updatedAt() == null ? "-" : UPDATED_AT_FORMATTER.format(note.updatedAt()),
                targetExists,
                targetExists ? stickyNoteService.openUrl(note.context()) : null
        );
    }

    private boolean matches(StickyNoteCatalogItemPayload item, String query) {
        return item.summary().toLowerCase(Locale.ROOT).contains(query)
                || item.contextLabel().toLowerCase(Locale.ROOT).contains(query)
                || item.targetType().toLowerCase(Locale.ROOT).contains(query)
                || item.surfaceLabel().toLowerCase(Locale.ROOT).contains(query);
    }
}
