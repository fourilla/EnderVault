package io.github.fourilla.endervault.storage;

import java.time.Instant;

public record FileItem(
        String name,
        String path,
        boolean directory,
        long size,
        String sizeLabel,
        String modifiedLabel,
        Instant modifiedAt,
        String mediaType,
        boolean previewable,
        boolean streamable
) {
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

    public String parentPath() {
        int index = path.lastIndexOf('/');
        return index < 0 ? "" : path.substring(0, index);
    }

    public String typeLabel() {
        if (directory) {
            return "Directory";
        }
        if (image()) {
            return "Image";
        }
        if (video()) {
            return "Video";
        }
        if (text()) {
            return "Text";
        }
        if (pdf()) {
            return "PDF";
        }
        return "File";
    }

    public String extensionLabel() {
        if (directory) {
            return typeLabel();
        }

        int extensionIndex = name.lastIndexOf('.');
        if (extensionIndex <= 0 || extensionIndex == name.length() - 1) {
            return typeLabel();
        }

        return name.substring(extensionIndex + 1).toUpperCase();
    }
}
