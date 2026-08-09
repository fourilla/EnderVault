package io.github.fourilla.endervault.remote;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RemoteDownloadRequestParser {

    public static final int MAX_CONNECTIONS = 8;
    private static final int MAX_HEADER_COUNT = 64;
    private static final int MAX_HEADER_NAME_LENGTH = 128;
    private static final int MAX_HEADER_VALUE_LENGTH = 8_192;
    private static final int MAX_TOTAL_HEADER_LENGTH = 32_768;
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "accept-encoding",
            "connection",
            "content-length",
            "forwarded",
            "host",
            "if-range",
            "proxy-authorization",
            "proxy-connection",
            "range",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "via",
            "x-http-method",
            "x-http-method-override",
            "x-method-override",
            "x-forwarded-for",
            "x-forwarded-host",
            "x-forwarded-proto"
    );

    private final RemoteDownloadValidator validator;

    public RemoteDownloadRequestParser(RemoteDownloadValidator validator) {
        this.validator = validator;
    }

    public RemoteDownloadRequestSpec parse(
            String rawUrl,
            String targetDirectory,
            NetworkRoute networkRoute,
            int connections,
            ConflictPolicy conflictPolicy,
            boolean skipInspection,
            String customHeaders
    ) {
        if (connections < 1 || connections > MAX_CONNECTIONS) {
            throw new StorageAccessException("Connections must be between 1 and " + MAX_CONNECTIONS + ".");
        }
        if (skipInspection && connections != 1) {
            throw new StorageAccessException("Skipping inspection requires exactly 1 connection.");
        }
        if (conflictPolicy == null) {
            throw new StorageAccessException("A conflict policy is required.");
        }
        String cleanUrl = clean(rawUrl);
        if (!StringUtils.hasText(cleanUrl)) {
            throw new StorageAccessException("Remote URL is required.");
        }

        Map<String, String> headers = parseHeaderLines(customHeaders);
        validateHeaderLimits(headers);
        URI sourceUri = validator.validate(cleanUrl);
        return new RemoteDownloadRequestSpec(
                sourceUri,
                targetDirectory == null ? "" : targetDirectory.trim(),
                networkRoute,
                connections,
                conflictPolicy,
                skipInspection,
                headers
        );
    }

    private Map<String, String> parseHeaderLines(String rawHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!StringUtils.hasText(rawHeaders)) {
            return headers;
        }
        for (String line : rawHeaders.split("\\R")) {
            if (!line.isBlank()) {
                addHeaderLine(headers, line);
            }
        }
        return headers;
    }

    private void addHeaderLine(Map<String, String> headers, String line) {
        int separator = line.indexOf(':');
        if (separator <= 0) {
            throw new StorageAccessException("Custom headers must use the Name: value format.");
        }
        addHeader(headers, line.substring(0, separator), line.substring(separator + 1));
    }

    private void addHeader(Map<String, String> headers, String rawName, String rawValue) {
        String name = clean(rawName);
        String value = rawValue == null ? "" : rawValue.trim();
        String normalized = name.toLowerCase(Locale.ROOT);
        if (name.length() > MAX_HEADER_NAME_LENGTH || !HEADER_NAME.matcher(name).matches()) {
            throw new StorageAccessException("Custom header name is invalid.");
        }
        if (FORBIDDEN_HEADERS.contains(normalized)
                || normalized.startsWith("proxy-")
                || normalized.startsWith("x-forwarded-")) {
            throw new StorageAccessException("Custom header is managed by EnderVault: " + name);
        }
        if (value.isBlank() || value.length() > MAX_HEADER_VALUE_LENGTH
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new StorageAccessException("Custom header value is invalid: " + name);
        }
        if (headers.keySet().stream().anyMatch(existing -> existing.equalsIgnoreCase(name))) {
            throw new StorageAccessException("Custom header is duplicated: " + name);
        }
        headers.put(name, value);
    }

    private void validateHeaderLimits(Map<String, String> headers) {
        if (headers.size() > MAX_HEADER_COUNT) {
            throw new StorageAccessException("Too many custom headers.");
        }
        int totalLength = headers.entrySet().stream()
                .mapToInt(entry -> entry.getKey().length() + entry.getValue().length())
                .sum();
        if (totalLength > MAX_TOTAL_HEADER_LENGTH) {
            throw new StorageAccessException("Custom headers are too large.");
        }
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
