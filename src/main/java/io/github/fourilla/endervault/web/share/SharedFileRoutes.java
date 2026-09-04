package io.github.fourilla.endervault.web.share;

import java.nio.file.Path;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
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
        String baseUrl = comicPageUrl(token, path, item);
        return baseUrl + (baseUrl.contains("?") ? "&" : "?") + "page=";
    }

    static String comicPageUrl(String token, String path, String item) {
        return comicUrl("page", token, path, item);
    }

    static String comicManifestUrl(String token, String path, String item) {
        return comicUrl("manifest", token, path, item);
    }

    private static String comicUrl(String resource, String token, String path, String item) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/s/{token}/comic/" + resource);
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("token", token);
        if (path != null && !path.isBlank()) {
            builder.queryParam("path", "{path}");
            variables.put("path", path);
        }
        if (item != null && !item.isBlank()) {
            builder.queryParam("item", "{item}");
            variables.put("item", item);
        }
        return builder.encode()
                .buildAndExpand(variables)
                .toUriString();
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
