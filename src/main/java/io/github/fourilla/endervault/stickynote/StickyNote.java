package io.github.fourilla.endervault.stickynote;

import java.time.Instant;

public record StickyNote(
        String id,
        StickyNoteContext context,
        String content,
        int x,
        int y,
        int width,
        int height,
        boolean collapsed,
        int layer,
        long revision,
        Instant createdAt,
        Instant updatedAt
) {
    public StickyNote withSnapshot(StickyNoteSnapshot snapshot, Instant now) {
        return new StickyNote(
                id,
                context,
                snapshot.content(),
                snapshot.x(),
                snapshot.y(),
                snapshot.width(),
                snapshot.height(),
                snapshot.collapsed(),
                snapshot.layer(),
                revision + 1L,
                createdAt,
                now
        );
    }

    public StickyNote withContext(StickyNoteContext nextContext, Instant now) {
        return new StickyNote(
                id,
                nextContext,
                content,
                x,
                y,
                width,
                height,
                collapsed,
                layer,
                revision + 1L,
                createdAt,
                now
        );
    }

    public String summary() {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return "Empty note";
        }
        return normalized.length() <= 100 ? normalized : normalized.substring(0, 97) + "...";
    }
}
