package io.github.fourilla.endervault.filetool.archive;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import java.util.Comparator;
import java.util.List;

public record ArchiveManifest(
        ArchiveFormat format,
        List<ArchiveEntryInfo> entries,
        int sourceEntryCount,
        int fileCount,
        int directoryCount,
        long totalUncompressedBytes,
        int rejectedEntryCount,
        boolean browsable,
        boolean extractable,
        String message
) {
    public ArchiveManifest {
        entries = List.copyOf(entries);
        message = message == null ? "" : message;
    }

    public List<ArchiveEntryInfo> children(String requestedParent) {
        String parent = normalizedParent(requestedParent);
        return entries.stream()
                .filter(entry -> entry.parentPath().equals(parent))
                .sorted(Comparator.comparing(ArchiveEntryInfo::directory).reversed()
                        .thenComparing(ArchiveEntryInfo::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ArchiveEntryInfo::name))
                .toList();
    }

    public String normalizedParent(String requestedParent) {
        return ArchivePathPolicy.normalizeParent(requestedParent);
    }

    public String totalSizeLabel() {
        return ByteSizeFormatter.humanSize(totalUncompressedBytes);
    }
}
