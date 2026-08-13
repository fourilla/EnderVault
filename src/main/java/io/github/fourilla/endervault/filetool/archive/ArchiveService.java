package io.github.fourilla.endervault.filetool.archive;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.task.TaskContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ArchiveService {

    private final Map<ArchiveFormat, ArchiveBackend> backends = new EnumMap<>(ArchiveFormat.class);
    private final ArchiveLimits limits;
    private final int cacheEntries;
    private final Map<Path, CachedManifest> cache;

    public ArchiveService(List<ArchiveBackend> backends, NasProperties nasProperties) {
        for (ArchiveBackend backend : backends) {
            for (ArchiveFormat format : backend.formats()) {
                ArchiveBackend previous = this.backends.put(format, backend);
                if (previous != null) {
                    throw new IllegalStateException("Multiple archive backends support " + format);
                }
            }
        }
        this.limits = ArchiveLimits.from(nasProperties.getFileTools());
        this.cacheEntries = Math.max(1, nasProperties.getFileTools().getArchiveManifestCacheEntries());
        this.cache = new LinkedHashMap<>(16, 0.75f, true);
    }

    public ArchiveManifest manifest(Path archive) throws IOException {
        Path key = archive.toAbsolutePath().normalize();
        ArchiveFormat format = requireFormat(key);
        long size = Files.size(key);
        FileTime modified = Files.getLastModifiedTime(key);
        synchronized (cache) {
            CachedManifest cached = cache.get(key);
            if (cached != null && cached.matches(size, modified)) {
                return cached.manifest();
            }
        }

        ArchiveManifest manifest = backend(format).scan(key, format, limits);
        synchronized (cache) {
            cache.put(key, new CachedManifest(size, modified, manifest));
            while (cache.size() > cacheEntries) {
                Path eldest = cache.keySet().iterator().next();
                cache.remove(eldest);
            }
        }
        return manifest;
    }

    public void extract(Path archive, Path outputRoot, TaskContext context) throws IOException {
        Path key = archive.toAbsolutePath().normalize();
        ArchiveFormat format = requireFormat(key);
        ArchiveManifest manifest = manifest(key);
        if (!manifest.extractable()) {
            throw new StorageAccessException(
                    manifest.message().isBlank() ? "This archive cannot be extracted safely." : manifest.message()
            );
        }
        ArchiveExtractionProgress progress = ArchiveExtractionProgress.task(context);
        progress.setTotalItems(manifest.sourceEntryCount());
        progress.setTotalBytes(manifest.totalUncompressedBytes());
        backend(format).extract(key, outputRoot.toAbsolutePath().normalize(), format, limits, progress);
    }

    public ArchiveFormat requireFormat(Path archive) {
        return ArchiveFormat.fromFilename(archive.getFileName().toString())
                .orElseThrow(() -> new StorageAccessException("This archive format is not supported."));
    }

    public String suggestedDirectoryName(Path archive) {
        ArchiveFormat format = requireFormat(archive);
        return format.suggestedDirectoryName(archive.getFileName().toString());
    }

    public void invalidate(Path archive) {
        synchronized (cache) {
            cache.remove(archive.toAbsolutePath().normalize());
        }
    }

    private ArchiveBackend backend(ArchiveFormat format) {
        ArchiveBackend backend = backends.get(format);
        if (backend == null) {
            throw new StorageAccessException("No archive backend is available for " + format.label() + ".");
        }
        return backend;
    }

    private record CachedManifest(long size, FileTime modified, ArchiveManifest manifest) {
        boolean matches(long candidateSize, FileTime candidateModified) {
            return size == candidateSize && modified.equals(candidateModified);
        }
    }
}
