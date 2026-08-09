package io.github.fourilla.endervault.remote;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RemoteDownloadFileNameResolver {

    private static final DateTimeFormatter FALLBACK_NAME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneId.systemDefault());

    String fileName(RemoteHttpResponse response) {
        String headerName = response.contentDispositionFileName();
        if (StringUtils.hasText(headerName)) {
            return cleanFileName(headerName);
        }
        return fileName(response.uri(), response.contentType());
    }

    String fileName(URI uri, String contentType) {
        String path = uri == null ? "" : uri.getPath();
        int slashIndex = path == null ? -1 : path.lastIndexOf('/');
        String lastSegment = slashIndex < 0 ? path : path.substring(slashIndex + 1);
        if (StringUtils.hasText(lastSegment)) {
            return withExtensionFromContentType(cleanFileName(decodeUrlSegment(lastSegment)), contentType);
        }
        return withExtensionFromContentType(fallbackName(), contentType);
    }

    String targetPath(String directory, String fileName) {
        if (!StringUtils.hasText(directory)) {
            return fileName;
        }
        return directory.replace('\\', '/').replaceAll("/+$", "") + "/" + fileName;
    }

    private String decodeUrlSegment(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }

    private String cleanFileName(String rawName) {
        String cleaned = rawName == null ? "" : rawName.trim();
        cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        cleaned = cleaned.replaceAll("\\s+", " ");
        while (cleaned.endsWith(".") || cleaned.endsWith(" ")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (!StringUtils.hasText(cleaned) || ".".equals(cleaned) || "..".equals(cleaned)) {
            return fallbackName();
        }
        return cleaned;
    }

    private String withExtensionFromContentType(String fileName, String contentType) {
        if (!StringUtils.hasText(fileName) || fileName.contains(".")) {
            return fileName;
        }
        String extension = extensionForContentType(contentType);
        return StringUtils.hasText(extension) ? fileName + "." + extension : fileName;
    }

    private String extensionForContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return "";
        }
        String normalized = contentType.toLowerCase().split(";", 2)[0].trim();
        return switch (normalized) {
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "video/x-matroska" -> "mkv";
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "text/plain" -> "txt";
            case "text/html" -> "html";
            case "application/pdf" -> "pdf";
            case "application/zip" -> "zip";
            case "application/json" -> "json";
            default -> "";
        };
    }

    private String fallbackName() {
        return "remote-download-" + FALLBACK_NAME_FORMATTER.format(Instant.now());
    }
}
