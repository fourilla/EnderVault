package io.github.fourilla.endervault.thumbnail;

import java.util.List;

public record ThumbnailCacheScan(
        List<ThumbnailCacheFile> orphanFiles,
        List<ThumbnailCacheFile> temporaryFiles
) {
}
