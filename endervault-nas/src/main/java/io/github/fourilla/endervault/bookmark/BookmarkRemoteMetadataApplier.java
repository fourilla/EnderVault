package io.github.fourilla.endervault.bookmark;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

final class BookmarkRemoteMetadataApplier {

    private final BookmarkInputNormalizer inputNormalizer;
    private final BookmarkMetadataFetcher metadataFetcher;
    private final BookmarkFaviconCacheService faviconCacheService;
    private final BooleanSupplier metadataFetchEnabled;

    BookmarkRemoteMetadataApplier(
            BookmarkInputNormalizer inputNormalizer,
            BookmarkMetadataFetcher metadataFetcher,
            BookmarkFaviconCacheService faviconCacheService,
            BooleanSupplier metadataFetchEnabled
    ) {
        this.inputNormalizer = inputNormalizer;
        this.metadataFetcher = metadataFetcher;
        this.faviconCacheService = faviconCacheService;
        this.metadataFetchEnabled = metadataFetchEnabled;
    }

    BookmarkItem tryApply(BookmarkItem bookmark) {
        return tryApply(bookmark, () -> false);
    }

    BookmarkItem tryApply(BookmarkItem bookmark, BooleanSupplier cancellationRequested) {
        if (!metadataFetchEnabled.getAsBoolean() || !bookmark.externalLink()) {
            return bookmark;
        }

        try {
            return refresh(bookmark, cancellationRequested);
        } catch (IOException | StorageAccessException ex) {
            Instant now = Instant.now();
            return bookmark.withRemoteMetadata(
                    bookmark.title(),
                    bookmark.effectiveTitleSource(),
                    bookmark.faviconFileName(),
                    bookmark.faviconContentType(),
                    now,
                    "FAILED: " + truncateStatus(ex.getMessage()),
                    now
            );
        }
    }

    BookmarkItem refresh(BookmarkItem bookmark) throws IOException {
        return refresh(bookmark, () -> false);
    }

    BookmarkItem refresh(BookmarkItem bookmark, BooleanSupplier cancellationRequested) throws IOException {
        checkCanceled(cancellationRequested);
        BookmarkMetadataFetchResult result;
        try {
            result = metadataFetcher.fetch(bookmark.url());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new StorageAccessException("Bookmark metadata fetch was interrupted.");
        }
        checkCanceled(cancellationRequested);

        String nextTitle = bookmark.title();
        BookmarkTitleSource nextTitleSource = bookmark.effectiveTitleSource();
        if (result.hasTitle() && nextTitleSource != BookmarkTitleSource.MANUAL) {
            nextTitle = inputNormalizer.normalizeRemoteTitle(result.title());
            nextTitleSource = BookmarkTitleSource.REMOTE_TITLE;
        }

        String faviconFileName = bookmark.faviconFileName();
        String faviconContentType = bookmark.faviconContentType();
        if (result.hasFavicon()) {
            checkCanceled(cancellationRequested);
            BookmarkMetadataFetchResult.Favicon favicon = result.favicon();
            BookmarkFaviconCacheEntry cachedFavicon = faviconCacheService.cache(favicon);
            checkCanceled(cancellationRequested);
            faviconFileName = cachedFavicon.fileName();
            faviconContentType = cachedFavicon.contentType();
        }

        Instant now = Instant.now();
        return bookmark.withRemoteMetadata(
                nextTitle,
                nextTitleSource,
                faviconFileName,
                faviconContentType,
                now,
                "OK",
                now
        );
    }

    private void checkCanceled(BooleanSupplier cancellationRequested) {
        if (Thread.currentThread().isInterrupted()
                || (cancellationRequested != null && cancellationRequested.getAsBoolean())) {
            throw new CancellationException("Bookmark link creation was canceled.");
        }
    }

    private String truncateStatus(String status) {
        String normalized = status == null ? "Unknown error" : status.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200).trim();
    }
}
