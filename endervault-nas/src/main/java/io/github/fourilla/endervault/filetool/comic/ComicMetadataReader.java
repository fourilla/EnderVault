package io.github.fourilla.endervault.filetool.comic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ComicMetadataReader {

    private ComicMetadataReader() {
    }

    static boolean isMetadataEntry(String entryName) {
        return "info.txt".equalsIgnoreCase(ComicArchiveEntries.displayName(entryName));
    }

    static ComicMetadata read(ZipFile zipFile, ZipEntry entry, long configuredMaxBytes) throws IOException {
        int maxBytes = maxBytes(configuredMaxBytes);
        int readLimit = maxBytes + 1;
        byte[] bytes;
        try (InputStream inputStream = zipFile.getInputStream(entry)) {
            bytes = inputStream.readNBytes(readLimit);
        }

        boolean truncated = bytes.length > maxBytes;
        int textLength = truncated ? maxBytes : bytes.length;
        String rawText = new String(bytes, 0, textLength, StandardCharsets.UTF_8);
        return new ComicMetadata(true, truncated, rawText, parseEntries(rawText));
    }

    private static int maxBytes(long configuredMaxBytes) {
        long minimum = Math.max(1024L, configuredMaxBytes);
        return (int) Math.min(minimum, Integer.MAX_VALUE - 1L);
    }

    private static List<ComicMetadataEntry> parseEntries(String rawText) {
        List<ComicMetadataEntry> entries = new ArrayList<>();
        for (String line : rawText.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            int separatorIndex = separatorIndex(trimmed);
            if (separatorIndex <= 0 || separatorIndex == trimmed.length() - 1) {
                continue;
            }

            String name = trimmed.substring(0, separatorIndex).trim();
            String value = trimmed.substring(separatorIndex + 1).trim();
            if (!name.isEmpty() && !value.isEmpty()) {
                entries.add(new ComicMetadataEntry(name, value));
            }
        }
        return List.copyOf(entries);
    }

    private static int separatorIndex(String line) {
        int colon = line.indexOf(':');
        int equals = line.indexOf('=');
        if (colon < 0) {
            return equals;
        }
        if (equals < 0) {
            return colon;
        }
        return Math.min(colon, equals);
    }
}
