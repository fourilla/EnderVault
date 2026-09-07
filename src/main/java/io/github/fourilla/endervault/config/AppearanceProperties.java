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
        @DefaultValue("#0F141A") String background,
        @DefaultValue("#171D24") String panel,
        @DefaultValue("#1D2530") String panelElevated,
        @DefaultValue("#202933") String panelMuted,
        @DefaultValue("#2F3A47") String border,
        @DefaultValue("#EDF3F7") String text,
        @DefaultValue("#9BA8B7") String mutedText,
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
        background = color(background);
        panel = color(panel);
        panelElevated = color(panelElevated);
        panelMuted = color(panelMuted);
        border = color(border);
        text = color(text);
        mutedText = color(mutedText);
        if (!SIZES.contains(controlSize) || !SIZES.contains(cardSize)) {
            throw new IllegalArgumentException("Appearance size is invalid.");
        }
    }

    public static AppearanceProperties defaults() {
        return new AppearanceProperties("#5BBDB4", "#79D2C8", "#5BBDB4", "#79D2C8", "#071617",
                "#0F141A", "#171D24", "#1D2530", "#202933", "#2F3A47", "#EDF3F7", "#9BA8B7", "medium", "medium");
    }

    public Map<String, String> propertyValues() {
        return Map.ofEntries(
                Map.entry("nas.appearance.accent", accent), Map.entry("nas.appearance.accent-strong", accentStrong),
                Map.entry("nas.appearance.button", button), Map.entry("nas.appearance.button-hover", buttonHover),
                Map.entry("nas.appearance.button-text", buttonText), Map.entry("nas.appearance.control-size", controlSize),
                Map.entry("nas.appearance.card-size", cardSize), Map.entry("nas.appearance.background", background),
                Map.entry("nas.appearance.panel", panel), Map.entry("nas.appearance.panel-elevated", panelElevated),
                Map.entry("nas.appearance.panel-muted", panelMuted), Map.entry("nas.appearance.border", border),
                Map.entry("nas.appearance.text", text), Map.entry("nas.appearance.muted-text", mutedText));
    }

    private static String color(String value) {
        if (value == null || !value.matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("Appearance colors must use the #RRGGBB format.");
        }
        return value.toUpperCase(Locale.ROOT);
    }
}
