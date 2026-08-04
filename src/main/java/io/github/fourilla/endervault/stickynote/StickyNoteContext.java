package io.github.fourilla.endervault.stickynote;

public record StickyNoteContext(
        StickyNoteTargetType targetType,
        String targetKey,
        StickyNoteSurface surface
) {
    public String normalizedTargetKey() {
        return targetKey == null ? "" : targetKey.trim();
    }
}
