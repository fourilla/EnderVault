package io.github.fourilla.endervault.storage;

public record FileItem(
        String name,
        String path,
        boolean directory,
        long size,
        String sizeLabel,
        String modifiedLabel,
        String mediaType,
        boolean previewable,
        boolean streamable
) {
}

