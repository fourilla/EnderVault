package io.github.fourilla.endervault.filetool;

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

    static int compareNaturally(String left, String right) {
        String a = left.toLowerCase(Locale.ROOT);
        String b = right.toLowerCase(Locale.ROOT);
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ac = a.charAt(i);
            char bc = b.charAt(j);
            if (Character.isDigit(ac) && Character.isDigit(bc)) {
                int result = compareNumberToken(a, i, b, j);
                if (result != 0) {
                    return result;
                }
                i = skipDigits(a, i);
                j = skipDigits(b, j);
                continue;
            }
            if (ac != bc) {
                return Character.compare(ac, bc);
            }
            i++;
            j++;
        }
        return Integer.compare(a.length(), b.length());
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

    private static int compareNumberToken(String a, int aStart, String b, int bStart) {
        int aEnd = skipDigits(a, aStart);
        int bEnd = skipDigits(b, bStart);
        String aNumber = stripLeadingZeroes(a.substring(aStart, aEnd));
        String bNumber = stripLeadingZeroes(b.substring(bStart, bEnd));
        int lengthCompare = Integer.compare(aNumber.length(), bNumber.length());
        if (lengthCompare != 0) {
            return lengthCompare;
        }
        int valueCompare = aNumber.compareTo(bNumber);
        if (valueCompare != 0) {
            return valueCompare;
        }
        return Integer.compare(aEnd - aStart, bEnd - bStart);
    }

    private static int skipDigits(String value, int start) {
        int index = start;
        while (index < value.length() && Character.isDigit(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private static String stripLeadingZeroes(String value) {
        int index = 0;
        while (index < value.length() - 1 && value.charAt(index) == '0') {
            index++;
        }
        return value.substring(index);
    }
}
