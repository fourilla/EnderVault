package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.config.NasProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(basePackages = "io.github.fourilla.endervault.web")
public class AppearanceModelAdvice {
    private final NasProperties properties;
    private final AppearanceThemeService themes;

    public AppearanceModelAdvice(NasProperties properties, AppearanceThemeService themes) {
        this.properties = properties;
        this.themes = themes;
    }

    @ModelAttribute("appearanceStylesheet")
    public String appearanceStylesheet() {
        return themes.stylesheet(properties.getAppearance());
    }
}
