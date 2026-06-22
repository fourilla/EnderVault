package io.github.fourilla.endervault.bookmark;

import io.github.fourilla.endervault.bookmark.BookmarkService.BulkLinkInput;
import io.github.fourilla.endervault.common.StorageAccessException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

final class BookmarkInputNormalizer {

    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_URL_LENGTH = 4096;
    private static final int MAX_NOTE_LENGTH = 1000;

    String normalizeTitle(String title) {
        String normalized = title == null ? "" : title.trim();
        if (normalized.isBlank()) {
            throw new StorageAccessException("Bookmark title is required.");
        }
        if (normalized.length() > MAX_TITLE_LENGTH) {
            throw new StorageAccessException("Bookmark title is too long.");
        }
        return normalized;
    }

    void validateBulkInput(BulkLinkInput input) {
        normalizeUrl(input.url());
        if (input.title() != null && !input.title().isBlank()) {
            normalizeTitle(input.title());
        }
    }

    BookmarkTitleChoice titleChoice(String title, String normalizedUrl) {
        String normalizedTitle = title == null ? "" : title.trim();
        if (!normalizedTitle.isBlank()) {
            return new BookmarkTitleChoice(normalizeTitle(normalizedTitle), BookmarkTitleSource.MANUAL);
        }
        return new BookmarkTitleChoice(deriveTitleFromUrl(normalizedUrl), BookmarkTitleSource.URL_DERIVED);
    }

    String normalizeRemoteTitle(String title) {
        String normalized = title == null ? "" : title.trim().replaceAll("\\s+", " ");
        return normalized.isBlank() ? "Bookmark" : truncateTitle(normalized);
    }

    String normalizeUrl(String url) {
        String normalized = url == null ? "" : url.trim();
        if (normalized.isBlank()) {
            throw new StorageAccessException("Bookmark URL is required.");
        }
        if (normalized.length() > MAX_URL_LENGTH) {
            throw new StorageAccessException("Bookmark URL is too long.");
        }
        try {
            URI uri = new URI(normalized);
            if (normalized.startsWith("/") && !normalized.startsWith("//")) {
                if (uri.getScheme() != null || uri.getHost() != null || containsControlOrBackslash(normalized)) {
                    throw new StorageAccessException("Bookmark URL is invalid.");
                }
                return uri.toString();
            }

            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new StorageAccessException("Bookmark URL must use http or https.");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new StorageAccessException("Bookmark URL host is required.");
            }
            if (uri.getUserInfo() != null) {
                throw new StorageAccessException("Bookmark URL must not contain user info.");
            }
            return uri.toString();
        } catch (URISyntaxException ex) {
            throw new StorageAccessException("Bookmark URL is invalid.", ex);
        }
    }

    boolean validUrlLine(String line) {
        try {
            normalizeUrl(line);
            return true;
        } catch (StorageAccessException ex) {
            return false;
        }
    }

    String normalizeNote(String note) {
        String normalized = note == null ? "" : note.trim();
        if (normalized.length() > MAX_NOTE_LENGTH) {
            throw new StorageAccessException("Bookmark note is too long.");
        }
        return normalized;
    }

    private String deriveTitleFromUrl(String normalizedUrl) {
        try {
            URI uri = new URI(normalizedUrl);
            String prefix = normalizedUrl.startsWith("/") ? "EnderVault" : uri.getHost();
            String path = uri.getPath();
            String suffix = path == null || path.isBlank() || "/".equals(path)
                    ? ""
                    : " / " + decodePath(path.replaceAll("^/+", "").replaceAll("/+$", ""));
            String title = (prefix == null || prefix.isBlank() ? "Bookmark" : prefix) + suffix;
            return truncateTitle(title);
        } catch (URISyntaxException ex) {
            return "Bookmark";
        }
    }

    private String decodePath(String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }

    private String truncateTitle(String title) {
        String normalized = title == null ? "Bookmark" : title.trim();
        if (normalized.length() <= MAX_TITLE_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_TITLE_LENGTH).trim();
    }

    private boolean containsControlOrBackslash(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch) || ch == '\\') {
                return true;
            }
        }
        return false;
    }

    record BookmarkTitleChoice(String title, BookmarkTitleSource source) {
    }
}
