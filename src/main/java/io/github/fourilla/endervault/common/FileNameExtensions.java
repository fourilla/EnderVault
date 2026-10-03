package io.github.fourilla.endervault.common;

import java.util.Locale;

public final class FileNameExtensions {
    private FileNameExtensions() {}

    public static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
