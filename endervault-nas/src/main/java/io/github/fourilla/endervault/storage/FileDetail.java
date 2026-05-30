package io.github.fourilla.endervault.storage;

public record FileDetail(
        String name,
        String path,
        String parentPath,
        boolean directory,
        long size,
        String sizeLabel,
        long childCount,
        String createdLabel,
        String modifiedLabel,
        String accessedLabel,
        String mediaType,
        String extension,
        boolean previewable,
        boolean streamable
) {
    public boolean hasParent() {
        return parentPath != null;
    }

    public boolean image() {
        return mediaType.startsWith("image/");
    }

    public boolean video() {
        return mediaType.startsWith("video/");
    }

    public boolean text() {
        return mediaType.startsWith("text/");
    }

    public boolean pdf() {
        return mediaType.equals("application/pdf");
    }
}
