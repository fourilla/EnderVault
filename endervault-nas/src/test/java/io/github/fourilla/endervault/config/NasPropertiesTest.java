package io.github.fourilla.endervault.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

class NasPropertiesTest {

    @Test
    void bindsTelegramActivityFlagsByReadablePropertyNames() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("nas.notifications.telegram.activity.login-success", "false")
                .withProperty("nas.notifications.telegram.activity.upload", "true")
                .withProperty("nas.notifications.telegram.activity.share-access", "true");

        NasProperties properties = Binder.get(environment)
                .bind("nas", NasProperties.class)
                .orElseGet(NasProperties::new);

        NasProperties.Telegram telegram = properties.getNotifications().getTelegram();
        assertThat(telegram.isActivityEnabled("LOGIN_SUCCESS")).isFalse();
        assertThat(telegram.isActivityEnabled("LOGIN_FAILURE")).isTrue();
        assertThat(telegram.isActivityEnabled("UPLOAD")).isTrue();
        assertThat(telegram.isActivityEnabled("SHARE_ACCESS")).isTrue();
        assertThat(telegram.isActivityEnabled("TRASH_EMPTY")).isFalse();
    }
}
