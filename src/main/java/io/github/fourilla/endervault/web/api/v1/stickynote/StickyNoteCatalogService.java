package io.github.fourilla.endervault.web.api.v1.stickynote;

import io.github.fourilla.endervault.stickynote.StickyNote;
import io.github.fourilla.endervault.stickynote.StickyNoteSearchSchema;
import io.github.fourilla.endervault.stickynote.StickyNoteSearchSchema.Candidate;
import io.github.fourilla.endervault.stickynote.StickyNoteService;
import io.github.fourilla.endervault.web.api.v1.stickynote.StickyNoteCatalogPayload.StickyNoteCatalogItemPayload;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Predicate;
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
        Predicate<Candidate> matches = StickyNoteSearchSchema.compile(query);
        return new StickyNoteCatalogPayload(stickyNoteService.listAll().stream()
                .map(note -> new Candidate(note, note.summary(), stickyNoteService.contextLabel(note.context())))
                .filter(matches)
                .map(this::item)
                .toList());
    }

    private StickyNoteCatalogItemPayload item(Candidate candidate) {
        StickyNote note = candidate.note();
        boolean targetExists = stickyNoteService.targetExists(note.context());
        return new StickyNoteCatalogItemPayload(
                note.id(),
                note.content(),
                candidate.summary(),
                candidate.targetLabel(),
                note.context().targetType().name(),
                note.context().surface().label(),
                note.updatedAt() == null ? "-" : UPDATED_AT_FORMATTER.format(note.updatedAt()),
                targetExists,
                targetExists ? stickyNoteService.openUrl(note.context()) : null
        );
    }
}
