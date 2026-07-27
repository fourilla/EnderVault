package io.github.fourilla.endervault.filetool;

import java.util.Locale;

public record FileToolContext(
        String name,
        boolean directory,
        String mediaType,
        String extension
) {
    public FileToolContext {
        name = name == null ? "" : name;
        mediaType = normalize(mediaType);
        extension = normalize(extension);
    }

    public String normalizedName() {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
