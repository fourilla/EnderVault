package io.github.fourilla.endervault.filetool;

public record FileToolDescriptor(
        FileToolType type,
        String id,
        String label,
        boolean previewable,
        boolean editable
) {
    public boolean directory() {
        return type == FileToolType.DIRECTORY;
    }

    public boolean image() {
        return type == FileToolType.IMAGE;
    }

    public boolean video() {
        return type == FileToolType.VIDEO;
    }

    public boolean text() {
        return type == FileToolType.TEXT;
    }

    public boolean pdf() {
        return type == FileToolType.PDF;
    }

    public boolean hex() {
        return type == FileToolType.HEX;
    }
}
