package io.github.fourilla.endervault.web.share;

import java.nio.file.Path;
import java.util.Locale;
import org.springframework.web.util.UriComponentsBuilder;

final class SharedFileRoutes {

    private SharedFileRoutes() {
    }

    static String itemVaultPath(String sharedBasePath, String path, String item) {
        StringBuilder builder = new StringBuilder();
        appendVaultPathSegment(builder, sharedBasePath);
        appendVaultPathSegment(builder, path);
        appendVaultPathSegment(builder, item);
        return builder.toString();
    }

    static String comicPageUrlPrefix(String token, String path, String item) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/s/{token}/comic/page");
        if (path != null && !path.isBlank()) {
            builder.queryParam("path", path);
        }
        if (item != null && !item.isBlank()) {
            builder.queryParam("item", item);
        }
        String baseUrl = builder.buildAndExpand(token)
                .encode()
                .toUriString();
        return baseUrl + (baseUrl.contains("?") ? "&" : "?") + "page=";
    }

    static String directoryUrl(String token, String path) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/s/{token}");
        if (path != null && !path.isBlank()) {
            builder.queryParam("path", path);
        }
        return builder.buildAndExpand(token)
                .encode()
                .toUriString();
    }

    static boolean isComic(Path file) {
        String filename = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return filename.endsWith(".cbz");
    }

    private static void appendVaultPathSegment(StringBuilder builder, String segment) {
        if (segment == null || segment.isBlank()) {
            return;
        }
        String cleaned = segment.replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('/');
        }
        builder.append(cleaned);
    }
}
