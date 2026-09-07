package io.github.fourilla.endervault.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.bind.DefaultValue;

public record AppearanceProperties(
        @DefaultValue("#5BBDB4") String accent,
        @DefaultValue("#79D2C8") String accentStrong,
        @DefaultValue("#5BBDB4") String button,
        @DefaultValue("#79D2C8") String buttonHover,
        @DefaultValue("#071617") String buttonText,
        @DefaultValue("medium") String controlSize,
        @DefaultValue("medium") String cardSize
) {
    private static final List<String> SIZES = List.of("extra-small", "small", "medium", "large", "extra-large");

    public AppearanceProperties {
        accent = color(accent);
        accentStrong = color(accentStrong);
        button = color(button);
        buttonHover = color(buttonHover);
        buttonText = color(buttonText);
        if (!SIZES.contains(controlSize) || !SIZES.contains(cardSize)) {
            throw new IllegalArgumentException("Appearance size is invalid.");
        }
    }

    public static AppearanceProperties defaults() {
        return new AppearanceProperties("#5BBDB4", "#79D2C8", "#5BBDB4", "#79D2C8", "#071617", "medium", "medium");
    }

    public Map<String, String> propertyValues() {
        return Map.of("nas.appearance.accent", accent, "nas.appearance.accent-strong", accentStrong,
                "nas.appearance.button", button, "nas.appearance.button-hover", buttonHover,
                "nas.appearance.button-text", buttonText, "nas.appearance.control-size", controlSize,
                "nas.appearance.card-size", cardSize);
    }

    private static String color(String value) {
        if (value == null || !value.matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("Appearance colors must use the #RRGGBB format.");
        }
        return value.toUpperCase(Locale.ROOT);
    }
}
