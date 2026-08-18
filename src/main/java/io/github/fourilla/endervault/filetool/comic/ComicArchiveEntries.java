package io.github.fourilla.endervault.filetool.comic;

import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;

final class ComicArchiveEntries {

    private static final Map<String, String> IMAGE_MEDIA_TYPES = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("avif", "image/avif"),
            Map.entry("bmp", "image/bmp")
    );

    private ComicArchiveEntries() {
    }

    static boolean isSafe(ZipEntry entry, String entryName) {
        return !entry.isDirectory()
                && !entryName.isBlank()
                && !entryName.startsWith("/")
                && !entryName.startsWith("\\")
                && !entryName.toLowerCase(Locale.ROOT).startsWith("__macosx/")
                && !entryName.contains(":")
                && segmentsAreSafe(entryName);
    }

    static String normalizeName(String entryName) {
        return entryName == null ? "" : entryName.replace('\\', '/');
    }

    static String displayName(String entryName) {
        int index = entryName.lastIndexOf('/');
        return index < 0 ? entryName : entryName.substring(index + 1);
    }

    static String mediaType(String entryName) {
        return IMAGE_MEDIA_TYPES.get(extensionOf(entryName));
    }

    private static boolean segmentsAreSafe(String entryName) {
        for (String segment : entryName.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private static String extensionOf(String entryName) {
        String displayName = displayName(entryName);
        int index = displayName.lastIndexOf('.');
        if (index <= 0 || index == displayName.length() - 1) {
            return "";
        }
        return displayName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

}
