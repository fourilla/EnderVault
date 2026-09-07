package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.config.AppearanceProperties;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AppearanceThemeService {
    private final ObjectMapper mapper;
    private final Definition definition;
    private volatile CachedStylesheet cached;

    public AppearanceThemeService(ObjectMapper mapper) throws IOException {
        this.mapper = mapper;
        try (var input = new ClassPathResource("appearance-tokens.json").getInputStream()) {
            definition = mapper.readValue(input, Definition.class);
        }
    }

    public String stylesheet(AppearanceProperties appearance) {
        var current = cached;
        if (current != null && current.appearance().equals(appearance)) {
            return current.css();
        }
        synchronized (this) {
            current = cached;
            if (current == null || !current.appearance().equals(appearance)) {
                current = new CachedStylesheet(appearance, renderStylesheet(appearance));
                cached = current;
            }
            return current.css();
        }
    }

    private String renderStylesheet(AppearanceProperties appearance) {
        // Only validated colors and bundled size/derived tokens enter the public document.
        var values = mapper.valueToTree(appearance);
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.putAll(definition.controls().get(appearance.controlSize()));
        tokens.putAll(definition.cards().get(appearance.cardSize()));
        tokens.putAll(definition.derived());
        definition.colors().forEach((token, field) -> tokens.put(token, values.get(field).asText()));
        return ":root { " + tokens.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue() + ";")
                .collect(Collectors.joining(" ")) + " }";
    }

    private record CachedStylesheet(AppearanceProperties appearance, String css) {
    }

    record Definition(Map<String, String> colors, Map<String, String> derived,
                      Map<String, Map<String, String>> controls, Map<String, Map<String, String>> cards) {
    }
}
