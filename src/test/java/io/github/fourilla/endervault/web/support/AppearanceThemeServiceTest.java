package io.github.fourilla.endervault.web.support;

import static org.assertj.core.api.Assertions.assertThat;
import io.github.fourilla.endervault.config.AppearanceProperties;
import io.github.fourilla.endervault.config.NasProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tools.jackson.databind.ObjectMapper;

class AppearanceThemeServiceTest {
    @Test
    void rendersValidatedColorsAndEverySizeFromSharedDefinition() throws Exception {
        var service = new AppearanceThemeService(new ObjectMapper());
        for (String size : List.of("extra-small", "small", "medium", "large", "extra-large")) {
            var appearance = new Binder(new MapConfigurationPropertySource(Map.of(
                    "nas.appearance.accent", "#ff3891", "nas.appearance.control-size", size,
                    "nas.appearance.card-size", size)))
                    .bind("nas.appearance", AppearanceProperties.class).get();
            String css = service.stylesheet(appearance);
            assertThat(css).startsWith(":root {").endsWith(" }")
                    .contains("--accent: #FF3891;", "--focus: #79D2C8;", "--control-height:",
                            "--browser-card-min-width:", "--table-cell-padding:")
                    .doesNotContain("null", "password", "<", ">");
        }
    }

    @Test
    void adviceReadsCurrentSnapshotInsteadOfCachingStartupSettings() throws Exception {
        var properties = new NasProperties();
        var advice = new AppearanceModelAdvice(properties, new AppearanceThemeService(new ObjectMapper()));
        assertThat(advice.appearanceStylesheet()).contains("--accent: #5BBDB4;");
        properties.setAppearance(new Binder(new MapConfigurationPropertySource(Map.of(
                "nas.appearance.accent", "#abcdef")))
                .bind("nas.appearance", AppearanceProperties.class).get());
        assertThat(advice.appearanceStylesheet()).contains("--accent: #ABCDEF;");
    }
}
