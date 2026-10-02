package io.github.fourilla.endervault.stickynote;

public record StickyNoteSnapshot(
        String content,
        int x,
        int y,
        Double xRatio,
        int width,
        int height,
        boolean collapsed,
        int layer
) {
}
