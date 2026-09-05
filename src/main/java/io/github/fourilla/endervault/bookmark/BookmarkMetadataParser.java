package io.github.fourilla.endervault.bookmark;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.JacksonException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;

final class BookmarkMetadataParser {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Pattern TITLE_PATTERN =
            Pattern.compile("(?is)<title\\b[^>]*>(.*?)</title>");
    private static final Pattern LINK_PATTERN =
            Pattern.compile("(?is)<link\\b[^>]*>");
    private static final Pattern META_PATTERN =
            Pattern.compile("(?is)<meta\\b[^>]*>");
    private static final Pattern SCRIPT_PATTERN =
            Pattern.compile("(?is)<script\\b([^>]*)>(.*?)</script>");
    private static final Pattern ATTRIBUTE_PATTERN =
            Pattern.compile("(?is)([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s\"'>]+)");
    private static final List<String> TITLE_METADATA_KEYS = List.of(
            "og:title",
            "twitter:title",
            "title",
            "application-name",
            "apple-mobile-web-app-title"
    );

    String extractTitle(String html) {
        for (String candidate : titleCandidates(html)) {
            String title = normalizeExtractedTitle(candidate);
            if (!title.isBlank()) {
                return title;
            }
        }
        return "";
    }

    List<URI> faviconCandidates(URI pageUri, String html) {
        List<URI> candidates = new ArrayList<>();
        Matcher matcher = LINK_PATTERN.matcher(html);
        while (matcher.find()) {
            Map<String, String> attributes = attributes(matcher.group());
            String rel = attributes.getOrDefault("rel", "").toLowerCase(Locale.ROOT);
            String href = attributes.get("href");
            if (href == null || href.isBlank() || !rel.contains("icon")) {
                continue;
            }
            candidates.add(pageUri.resolve(HtmlUtils.htmlUnescape(href.trim())));
        }
        candidates.add(pageUri.resolve("/favicon.ico"));
        return candidates.stream().distinct().toList();
    }

    List<URI> manifestCandidates(URI pageUri, String html) {
        List<URI> candidates = new ArrayList<>();
        Matcher matcher = LINK_PATTERN.matcher(html);
        while (matcher.find()) {
            Map<String, String> attributes = attributes(matcher.group());
            String rel = attributes.getOrDefault("rel", "").toLowerCase(Locale.ROOT);
            String href = attributes.get("href");
            if (href == null || href.isBlank() || !rel.contains("manifest")) {
                continue;
            }
            candidates.add(pageUri.resolve(HtmlUtils.htmlUnescape(href.trim())));
        }
        return candidates.stream().distinct().toList();
    }

    Optional<String> titleFromManifest(String manifest) {
        try {
            JsonNode root = JSON.readTree(stripJsonScriptWrappers(manifest));
            String title = normalizeExtractedTitle(text(root.get("name")));
            if (!title.isBlank()) {
                return Optional.of(title);
            }
            title = normalizeExtractedTitle(text(root.get("short_name")));
            return title.isBlank() ? Optional.empty() : Optional.of(title);
        } catch (JacksonException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private List<String> titleCandidates(String html) {
        List<String> candidates = new ArrayList<>();
        Map<String, String> metadataTitles = metadataTitles(html);
        addIfPresent(candidates, metadataTitles, "og:title");
        addIfPresent(candidates, metadataTitles, "twitter:title");
        addIfPresent(candidates, metadataTitles, "title");
        candidates.add(htmlTitle(html));
        candidates.addAll(jsonLdTitleCandidates(html));
        addIfPresent(candidates, metadataTitles, "application-name");
        addIfPresent(candidates, metadataTitles, "apple-mobile-web-app-title");
        return candidates;
    }

    private Map<String, String> attributes(String tag) {
        Map<String, String> attributes = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE_PATTERN.matcher(tag);
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase(Locale.ROOT);
            String value = matcher.group(2);
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            attributes.put(key, HtmlUtils.htmlUnescape(value.trim()));
        }
        return attributes;
    }

    private Map<String, String> metadataTitles(String html) {
        Map<String, String> titles = new LinkedHashMap<>();
        Matcher matcher = META_PATTERN.matcher(html);
        while (matcher.find()) {
            Map<String, String> attributes = attributes(matcher.group());
            String content = attributes.get("content");
            if (content == null || content.isBlank()) {
                continue;
            }
            addMetadataTitle(titles, attributes.get("property"), content);
            addMetadataTitle(titles, attributes.get("name"), content);
            addMetadataTitle(titles, attributes.get("itemprop"), content);
        }
        return titles;
    }

    private void addMetadataTitle(Map<String, String> titles, String key, String content) {
        String normalizedKey = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        if (TITLE_METADATA_KEYS.contains(normalizedKey)) {
            titles.putIfAbsent(normalizedKey, content);
        }
    }

    private void addIfPresent(List<String> candidates, Map<String, String> metadataTitles, String key) {
        String value = metadataTitles.get(key);
        if (value != null) {
            candidates.add(value);
        }
    }

    private String htmlTitle(String html) {
        Matcher matcher = TITLE_PATTERN.matcher(html);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1);
    }

    private List<String> jsonLdTitleCandidates(String html) {
        List<String> titles = new ArrayList<>();
        Matcher matcher = SCRIPT_PATTERN.matcher(html);
        while (matcher.find()) {
            Map<String, String> attributes = attributes(matcher.group(1));
            String type = attributes.getOrDefault("type", "").toLowerCase(Locale.ROOT);
            if (!type.contains("ld+json")) {
                continue;
            }
            try {
                JsonNode root = JSON.readTree(stripJsonScriptWrappers(matcher.group(2)));
                collectJsonLdTitles(root, titles, 0);
            } catch (JacksonException | IllegalArgumentException ignored) {
                // Ignore malformed structured data; plain HTML title candidates may still work.
            }
        }
        return titles;
    }

    private void collectJsonLdTitles(JsonNode node, List<String> titles, int depth) {
        if (node == null || node.isMissingNode() || depth > 4 || titles.size() >= 10) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectJsonLdTitles(child, titles, depth + 1));
            return;
        }
        if (!node.isObject()) {
            return;
        }

        addJsonText(titles, node.get("headline"));
        addJsonText(titles, node.get("name"));
        collectJsonLdTitles(node.get("@graph"), titles, depth + 1);
        collectJsonLdTitles(node.get("mainEntity"), titles, depth + 1);
        collectJsonLdTitles(node.get("about"), titles, depth + 1);
    }

    private void addJsonText(List<String> titles, JsonNode node) {
        if (node != null && node.isTextual()) {
            titles.add(node.asText());
        }
    }

    private String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : "";
    }

    private String stripJsonScriptWrappers(String value) {
        String normalized = value == null ? "" : HtmlUtils.htmlUnescape(value).trim();
        if (normalized.startsWith("<!--")) {
            normalized = normalized.substring(4).trim();
        }
        if (normalized.endsWith("-->")) {
            normalized = normalized.substring(0, normalized.length() - 3).trim();
        }
        return normalized;
    }

    private String normalizeExtractedTitle(String value) {
        String title = HtmlUtils.htmlUnescape(value == null ? "" : value)
                .replaceAll("\\s+", " ")
                .trim();
        return title.length() > 200 ? title.substring(0, 200).trim() : title;
    }
}
