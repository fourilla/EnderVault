package io.github.fourilla.endervault.filetool;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.storage.FileDetail;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.springframework.stereotype.Service;

@Service
public class ComicArchiveService {

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

        ComicZipEntryResource resource = new ComicZipEntryResource(
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
                String entryName = ComicArchiveEntries.normalizeName(entry.getName());
                if (!ComicArchiveEntries.isSafe(entry, entryName)) {
                    continue;
                }

                String mediaType = ComicArchiveEntries.mediaType(entryName);
                if (mediaType != null) {
                    imageEntries.add(new ComicEntryCandidate(
                            entryName,
                            ComicArchiveEntries.displayName(entryName),
                            mediaType,
                            entry.getSize()
                    ));
                    continue;
                }

                if (!metadata.present() && ComicMetadataReader.isMetadataEntry(entryName)) {
                    metadata = ComicMetadataReader.read(zipFile, entry, fileTools.getComicInfoMaxBytes());
                }
            }
        } catch (ZipException | IllegalArgumentException ex) {
            throw new StorageAccessException("This CBZ file could not be opened as a ZIP archive.", ex);
        }

        int maxPages = Math.max(1, fileTools.getComicMaxPages());
        if (imageEntries.size() > maxPages) {
            throw new StorageAccessException("This CBZ contains more than %d image pages.".formatted(maxPages));
        }

        imageEntries.sort(Comparator.comparing(
                ComicEntryCandidate::entryName,
                ComicArchiveEntries::compareNaturally
        ));
        List<ComicPage> pages = new ArrayList<>(imageEntries.size());
        for (int i = 0; i < imageEntries.size(); i++) {
            ComicEntryCandidate candidate = imageEntries.get(i);
            pages.add(new ComicPage(i, candidate.entryName(), candidate.displayName(), candidate.mediaType(), candidate.size()));
        }

        return new ComicArchiveManifest(pages.size(), List.copyOf(pages), metadata);
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
}
