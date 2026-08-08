package io.github.fourilla.endervault.web.stickynote;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.stickynote.StickyNote;
import io.github.fourilla.endervault.stickynote.StickyNoteContext;
import io.github.fourilla.endervault.stickynote.StickyNoteService;
import io.github.fourilla.endervault.stickynote.StickyNoteSnapshot;
import io.github.fourilla.endervault.stickynote.StickyNoteSurface;
import io.github.fourilla.endervault.stickynote.StickyNoteTargetType;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminStickyNoteController {

    private static final DateTimeFormatter UPDATED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final StickyNoteService stickyNoteService;
    private final ActivityLogService activityLogService;

    public AdminStickyNoteController(
            StickyNoteService stickyNoteService,
            ActivityLogService activityLogService
    ) {
        this.stickyNoteService = stickyNoteService;
        this.activityLogService = activityLogService;
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

    @GetMapping("/admin/sticky-notes/items")
    public ResponseEntity<StickyNoteResponse> items(
            @RequestParam StickyNoteTargetType targetType,
            @RequestParam(defaultValue = "") String targetKey,
            @RequestParam StickyNoteSurface surface
    ) {
        try {
            StickyNoteContext context = new StickyNoteContext(targetType, targetKey, surface);
            List<StickyNotePayload> notes = stickyNoteService.list(context).stream()
                    .map(StickyNotePayload::from)
                    .toList();
            return ResponseEntity.ok(StickyNoteResponse.list(notes));
        } catch (IOException | RuntimeException ex) {
            return error(ex);
        }
    }

    @PostMapping("/admin/sticky-notes/items")
    public ResponseEntity<StickyNoteResponse> create(
            @RequestBody StickyNoteCreateRequest payload,
            HttpServletRequest request
    ) {
        try {
            StickyNoteContext context = new StickyNoteContext(
                    payload.targetType(),
                    payload.targetKey(),
                    payload.surface()
            );
            StickyNote note = stickyNoteService.create(context, payload.x(), payload.y());
            activityLogService.record(
                    "STICKY_NOTE_CREATE",
                    request,
                    request.getRequestURI(),
                    context.targetKey(),
                    "Sticky note created.",
                    logMetadata(note)
            );
            return ResponseEntity.ok(StickyNoteResponse.one(
                    FlashNotification.success("Sticky note created."),
                    StickyNotePayload.from(note)
            ));
        } catch (IOException | RuntimeException ex) {
            return error(ex);
        }
    }

    @PutMapping("/admin/sticky-notes/items/{id}")
    public ResponseEntity<StickyNoteResponse> update(
            @PathVariable String id,
            @RequestBody StickyNoteUpdateRequest payload
    ) {
        try {
            StickyNote note = stickyNoteService.update(id, payload.snapshot());
            return ResponseEntity.ok(StickyNoteResponse.one(null, StickyNotePayload.from(note)));
        } catch (IOException | RuntimeException ex) {
            return error(ex);
        }
    }

    @PostMapping("/admin/sticky-notes/items/{id}/delete")
    public Object delete(
            @PathVariable String id,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        try {
            StickyNote note = stickyNoteService.delete(id);
            activityLogService.record(
                    "STICKY_NOTE_DELETE",
                    request,
                    request.getRequestURI(),
                    note.context().targetKey(),
                    "Sticky note deleted.",
                    logMetadata(note)
            );
            FlashNotification notification = FlashNotification.success("Sticky note deleted.");
            return ActionResponseSupport.ok(
                    request,
                    redirectAttributes,
                    notification,
                    "redirect:/admin/sticky-notes",
                    StickyNoteResponse.deleted(notification, note.id())
            );
        } catch (IOException | RuntimeException ex) {
            FlashNotification notification = FlashNotification.error(message(ex));
            return ActionResponseSupport.error(
                    ex instanceof StorageAccessException ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR,
                    request,
                    redirectAttributes,
                    notification,
                    "redirect:/admin/sticky-notes"
            );
        }
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

    private Map<String, String> logMetadata(StickyNote note) {
        return Map.of(
                "noteId", note.id(),
                "targetType", note.context().targetType().name(),
                "surface", note.context().surface().name()
        );
    }

    private ResponseEntity<StickyNoteResponse> error(Exception ex) {
        HttpStatus status = ex instanceof StorageAccessException ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(StickyNoteResponse.error(message(ex)));
    }

    private String message(Exception ex) {
        if (ex instanceof StorageAccessException && ex.getMessage() != null && !ex.getMessage().isBlank()) {
            return ex.getMessage();
        }
        return "Sticky note operation failed.";
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

    public record StickyNoteCreateRequest(
            StickyNoteTargetType targetType,
            String targetKey,
            StickyNoteSurface surface,
            int x,
            int y
    ) {
    }

    public record StickyNoteUpdateRequest(
            String content,
            int x,
            int y,
            int width,
            int height,
            boolean collapsed,
            int layer
    ) {
        StickyNoteSnapshot snapshot() {
            return new StickyNoteSnapshot(content, x, y, width, height, collapsed, layer);
        }
    }

    public record StickyNotePayload(
            String id,
            String content,
            int x,
            int y,
            int width,
            int height,
            boolean collapsed,
            int layer,
            long revision,
            String updatedAt
    ) {
        static StickyNotePayload from(StickyNote note) {
            return new StickyNotePayload(
                    note.id(),
                    note.content(),
                    note.x(),
                    note.y(),
                    note.width(),
                    note.height(),
                    note.collapsed(),
                    note.layer(),
                    note.revision(),
                    note.updatedAt() == null ? null : note.updatedAt().toString()
            );
        }
    }

    public record StickyNoteResponse(
            boolean ok,
            FlashNotification notification,
            StickyNotePayload note,
            List<StickyNotePayload> notes,
            String deletedId
    ) {
        static StickyNoteResponse list(List<StickyNotePayload> notes) {
            return new StickyNoteResponse(true, null, null, notes, null);
        }

        static StickyNoteResponse one(FlashNotification notification, StickyNotePayload note) {
            return new StickyNoteResponse(true, notification, note, List.of(), null);
        }

        static StickyNoteResponse deleted(FlashNotification notification, String id) {
            return new StickyNoteResponse(true, notification, null, List.of(), id);
        }

        static StickyNoteResponse error(String message) {
            return new StickyNoteResponse(false, FlashNotification.error(message), null, List.of(), null);
        }
    }
}
