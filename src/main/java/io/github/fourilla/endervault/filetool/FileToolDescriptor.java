package io.github.fourilla.endervault.filetool;

import java.util.Arrays;
import java.util.Set;

public record FileToolDescriptor(
        FileToolType type,
        String id,
        String label,
        Set<FileToolCapability> capabilities
) {
    public FileToolDescriptor {
        capabilities = Set.copyOf(capabilities);
    }

    public static FileToolDescriptor of(FileToolType type, FileToolCapability... capabilities) {
        return new FileToolDescriptor(type, type.id(), type.label(), Set.copyOf(Arrays.asList(capabilities)));
    }

    public boolean previewable() {
        return capabilities.contains(FileToolCapability.INLINE_PREVIEW);
    }

    public boolean previewPageAvailable() {
        return capabilities.contains(FileToolCapability.PREVIEW_PAGE);
    }

    public boolean editable() {
        return capabilities.contains(FileToolCapability.TEXT_EDIT);
    }

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

    public boolean comic() {
        return type == FileToolType.COMIC;
    }

    public boolean hex() {
        return type == FileToolType.HEX;
    }
}
