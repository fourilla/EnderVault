package io.github.fourilla.endervault.web.stickynote;

import io.github.fourilla.endervault.stickynote.StickyNote;
import io.github.fourilla.endervault.stickynote.StickyNoteService;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AdminStickyNoteController {

    private static final DateTimeFormatter UPDATED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final StickyNoteService stickyNoteService;

    public AdminStickyNoteController(StickyNoteService stickyNoteService) {
        this.stickyNoteService = stickyNoteService;
    }

    @GetMapping("/admin/sticky-notes")
    public String notes(
            @RequestParam(name = "q", required = false) String query,
            Model model
    ) throws IOException {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<StickyNoteManagerItem> notes = stickyNoteService.listAll().stream()
                .map(this::managerItem)
                .filter(item -> normalizedQuery.isBlank() || item.matches(normalizedQuery))
                .toList();
        model.addAttribute("notes", notes);
        model.addAttribute("q", query == null ? "" : query.trim());
        return "sticky-notes";
    }

    private StickyNoteManagerItem managerItem(StickyNote note) {
        boolean targetExists = stickyNoteService.targetExists(note.context());
        return new StickyNoteManagerItem(
                note,
                stickyNoteService.contextLabel(note.context()),
                note.context().surface().label(),
                note.updatedAt() == null ? "-" : UPDATED_AT_FORMATTER.format(note.updatedAt()),
                targetExists,
                targetExists ? stickyNoteService.openUrl(note.context()) : null
        );
    }

    public record StickyNoteManagerItem(
            StickyNote note,
            String contextLabel,
            String surfaceLabel,
            String updatedLabel,
            boolean targetExists,
            String openUrl
    ) {
        boolean matches(String query) {
            return note.summary().toLowerCase(Locale.ROOT).contains(query)
                    || contextLabel.toLowerCase(Locale.ROOT).contains(query)
                    || note.context().targetType().name().toLowerCase(Locale.ROOT).contains(query)
                    || surfaceLabel.toLowerCase(Locale.ROOT).contains(query);
        }
    }
}
