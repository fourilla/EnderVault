package io.github.fourilla.endervault.filetool;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.storage.FileDetail;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class ComicArchiveService {

    private static final Map<String, String> IMAGE_MEDIA_TYPES = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("avif", "image/avif"),
            Map.entry("bmp", "image/bmp")
    );

    private final NasProperties.FileTools fileTools;
    private final Map<Path, CachedManifest> cache = new ConcurrentHashMap<>();

    public ComicArchiveService(NasProperties nasProperties) {
        this.fileTools = nasProperties.getFileTools();
    }

    public boolean isComic(FileDetail detail) {
        return !detail.directory() && "cbz".equalsIgnoreCase(detail.extension());
    }

    public ComicArchiveManifest manifest(Path cbzFile) throws IOException {
        Path key = cbzFile.toAbsolutePath().normalize();
        long size = Files.size(key);
        FileTime modifiedTime = Files.getLastModifiedTime(key);
        CachedManifest cached = cache.get(key);
        if (cached != null && cached.matches(size, modifiedTime)) {
            return cached.manifest();
        }

        ComicArchiveManifest manifest = readManifest(key);
        cache.put(key, new CachedManifest(size, modifiedTime, manifest));
        return manifest;
    }

    public int normalizePage(ComicArchiveManifest manifest, Integer requestedPageNumber) {
        if (manifest.empty()) {
            return 0;
        }
        int pageNumber = requestedPageNumber == null ? 1 : requestedPageNumber;
        pageNumber = Math.max(1, Math.min(pageNumber, manifest.pageCount()));
        return pageNumber - 1;
    }

    public ComicPageResource openPage(Path cbzFile, int pageIndex) throws IOException {
        ComicArchiveManifest manifest = manifest(cbzFile);
        if (manifest.empty() || pageIndex < 0 || pageIndex >= manifest.pageCount()) {
            throw new StorageAccessException("Comic page is out of range.");
        }

        ComicPage page = manifest.page(pageIndex);
        long maxPageBytes = Math.max(1024L, fileTools.getComicPageMaxBytes());
        if (page.size() > maxPageBytes) {
            throw new StorageAccessException("Comic page is larger than the configured limit.");
        }

        Resource resource = new ComicZipEntryResource(
                cbzFile.toAbsolutePath().normalize(),
                page.entryName(),
                page.displayName(),
                page.size(),
                maxPageBytes
        );
        return new ComicPageResource(resource, page.mediaType(), page.size(), page.displayName());
    }

    private ComicArchiveManifest readManifest(Path cbzFile) throws IOException {
        List<ComicEntryCandidate> imageEntries = new ArrayList<>();
        ComicMetadata metadata = ComicMetadata.empty();

        try (ZipFile zipFile = new ZipFile(cbzFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = normalizeEntryName(entry.getName());
                if (!isSafeEntry(entry, entryName)) {
                    continue;
                }

                String extension = extensionOf(entryName);
                String mediaType = IMAGE_MEDIA_TYPES.get(extension);
                if (mediaType != null) {
                    imageEntries.add(new ComicEntryCandidate(entryName, displayName(entryName), mediaType, entry.getSize()));
                    continue;
                }

                if (!metadata.present() && "info.txt".equalsIgnoreCase(displayName(entryName))) {
                    metadata = readMetadata(zipFile, entry);
                }
            }
        } catch (ZipException | IllegalArgumentException ex) {
            throw new StorageAccessException("This CBZ file could not be opened as a ZIP archive.", ex);
        }

        int maxPages = Math.max(1, fileTools.getComicMaxPages());
        if (imageEntries.size() > maxPages) {
            throw new StorageAccessException("This CBZ contains more than %d image pages.".formatted(maxPages));
        }

        imageEntries.sort(Comparator.comparing(ComicEntryCandidate::entryName, ComicArchiveService::compareNatural));
        List<ComicPage> pages = new ArrayList<>(imageEntries.size());
        for (int i = 0; i < imageEntries.size(); i++) {
            ComicEntryCandidate candidate = imageEntries.get(i);
            pages.add(new ComicPage(i, candidate.entryName(), candidate.displayName(), candidate.mediaType(), candidate.size()));
        }

        return new ComicArchiveManifest(pages.size(), List.copyOf(pages), metadata);
    }

    private ComicMetadata readMetadata(ZipFile zipFile, ZipEntry entry) throws IOException {
        long configuredMaxBytes = Math.max(1024L, fileTools.getComicInfoMaxBytes());
        int maxBytes = (int) Math.min(configuredMaxBytes, Integer.MAX_VALUE - 1L);
        int readLimit = maxBytes + 1;
        byte[] bytes;
        try (InputStream inputStream = zipFile.getInputStream(entry)) {
            bytes = inputStream.readNBytes(readLimit);
        }

        boolean truncated = bytes.length > maxBytes;
        int textLength = truncated ? (int) maxBytes : bytes.length;
        String rawText = new String(bytes, 0, textLength, StandardCharsets.UTF_8);
        return new ComicMetadata(true, truncated, rawText, parseMetadataEntries(rawText));
    }

    private List<ComicMetadataEntry> parseMetadataEntries(String rawText) {
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

    private int separatorIndex(String line) {
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

    private boolean isSafeEntry(ZipEntry entry, String entryName) {
        return !entry.isDirectory()
                && !entryName.isBlank()
                && !entryName.startsWith("/")
                && !entryName.startsWith("\\")
                && !entryName.toLowerCase(Locale.ROOT).startsWith("__macosx/")
                && !entryName.contains(":")
                && entryNameSegmentsAreSafe(entryName);
    }

    private boolean entryNameSegmentsAreSafe(String entryName) {
        for (String segment : entryName.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private String normalizeEntryName(String entryName) {
        return entryName == null ? "" : entryName.replace('\\', '/');
    }

    private String displayName(String entryName) {
        int index = entryName.lastIndexOf('/');
        return index < 0 ? entryName : entryName.substring(index + 1);
    }

    private String extensionOf(String entryName) {
        String displayName = displayName(entryName);
        int index = displayName.lastIndexOf('.');
        if (index <= 0 || index == displayName.length() - 1) {
            return "";
        }
        return displayName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private static int compareNatural(String left, String right) {
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

    private record ComicEntryCandidate(
            String entryName,
            String displayName,
            String mediaType,
            long size
    ) {
    }

    private record CachedManifest(
            long size,
            FileTime modifiedTime,
            ComicArchiveManifest manifest
    ) {
        boolean matches(long candidateSize, FileTime candidateModifiedTime) {
            return size == candidateSize && modifiedTime.equals(candidateModifiedTime);
        }
    }

    private static class ComicZipEntryResource extends AbstractResource {
        private final Path cbzFile;
        private final String entryName;
        private final String filename;
        private final long contentLength;
        private final long maxBytes;

        ComicZipEntryResource(Path cbzFile, String entryName, String filename, long contentLength, long maxBytes) {
            this.cbzFile = cbzFile;
            this.entryName = entryName;
            this.filename = filename;
            this.contentLength = contentLength;
            this.maxBytes = maxBytes;
        }

        @Override
        public String getDescription() {
            return "CBZ entry " + entryName + " from " + cbzFile;
        }

        @Override
        public String getFilename() {
            return filename;
        }

        @Override
        public long contentLength() {
            return contentLength;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            ZipFile zipFile = new ZipFile(cbzFile.toFile());
            ZipEntry entry = zipFile.getEntry(entryName);
            if (entry == null) {
                zipFile.close();
                throw new StorageAccessException("Comic page is no longer available.");
            }

            InputStream inputStream = new BoundedInputStream(zipFile.getInputStream(entry), maxBytes);
            return new FilterInputStream(inputStream) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        zipFile.close();
                    }
                }
            };
        }
    }

    private static class BoundedInputStream extends FilterInputStream {
        private final long maxBytes;
        private long bytesRead;

        BoundedInputStream(InputStream inputStream, long maxBytes) {
            super(inputStream);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                countBytes(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }

            int boundedLength = boundedLength(length);
            int read = super.read(buffer, offset, boundedLength);
            if (read > 0) {
                countBytes(read);
            }
            return read;
        }

        @Override
        public long skip(long length) throws IOException {
            if (length <= 0) {
                return 0;
            }

            long skipped = super.skip(Math.min(length, remainingBytesPlusOverflowProbe()));
            if (skipped > 0) {
                countBytes(skipped);
            }
            return skipped;
        }

        private int boundedLength(int requestedLength) {
            return (int) Math.min(requestedLength, Math.min(Integer.MAX_VALUE, remainingBytesPlusOverflowProbe()));
        }

        private long remainingBytesPlusOverflowProbe() {
            long remaining = maxBytes - bytesRead;
            if (remaining >= Long.MAX_VALUE - 1L) {
                return Long.MAX_VALUE;
            }
            return Math.max(1L, remaining + 1L);
        }

        private void countBytes(long count) throws IOException {
            bytesRead += count;
            if (bytesRead > maxBytes) {
                throw new IOException("Comic page exceeded the configured size limit.");
            }
        }
    }
}
