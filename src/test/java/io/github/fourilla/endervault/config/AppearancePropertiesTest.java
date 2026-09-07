package io.github.fourilla.endervault.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AppearancePropertiesTest {
    @Test
    void bindsPartialConfigurationWithSafeDefaults() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "nas.appearance.control-size", "large", "nas.appearance.accent", "#abcdef")));
        NasProperties properties = binder.bind("nas", NasProperties.class).get();
        assertThat(properties.getAppearance().controlSize()).isEqualTo("large");
        assertThat(properties.getAppearance().cardSize()).isEqualTo("medium");
        assertThat(properties.getAppearance().accent()).isEqualTo("#ABCDEF");
        assertThat(properties.getAppearance().background()).isEqualTo("#0F141A");
        var restored = new Binder(new MapConfigurationPropertySource(properties.getAppearance().propertyValues()))
                .bind("nas.appearance", AppearanceProperties.class).get();
        assertThat(restored).isEqualTo(properties.getAppearance());
    }

    @Test
    void rejectsUnsafeConfigurationAtBinding() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("nas.appearance.accent", "url(example)")));
        assertThatThrownBy(() -> binder.bind("nas", NasProperties.class)).isInstanceOf(RuntimeException.class);
    }
}
