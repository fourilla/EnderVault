package io.github.fourilla.endervault.filetool.archive;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

public enum ArchiveFormat {
    TAR_GZIP("tar.gz", "TAR + Gzip", ".tar.gz", ".tgz"),
    TAR_BZIP2("tar.bz2", "TAR + Bzip2", ".tar.bz2", ".tbz2"),
    TAR_XZ("tar.xz", "TAR + XZ", ".tar.xz", ".txz"),
    SEVEN_ZIP("7z", "7-Zip", ".7z"),
    ZIP("zip", "ZIP", ".zip"),
    TAR("tar", "TAR", ".tar");

    private final String id;
    private final String label;
    private final String[] suffixes;

    ArchiveFormat(String id, String label, String... suffixes) {
        this.id = id;
        this.label = label;
        this.suffixes = suffixes;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public static Optional<ArchiveFormat> fromFilename(String filename) {
        String normalized = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .sorted(Comparator.comparingInt(ArchiveFormat::longestSuffixLength).reversed())
                .filter(format -> Arrays.stream(format.suffixes).anyMatch(normalized::endsWith))
                .findFirst();
    }

    public String suggestedDirectoryName(String filename) {
        String normalized = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        return Arrays.stream(suffixes)
                .filter(normalized::endsWith)
                .max(Comparator.comparingInt(String::length))
                .map(suffix -> filename.substring(0, filename.length() - suffix.length()))
                .filter(value -> !value.isBlank())
                .orElse("Extracted archive");
    }

    private int longestSuffixLength() {
        return Arrays.stream(suffixes).mapToInt(String::length).max().orElse(0);
    }
}
