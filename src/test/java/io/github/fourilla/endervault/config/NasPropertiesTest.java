package io.github.fourilla.endervault.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.outbound.NetworkRoute;
import java.util.List;
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

    @Test
    void bindsTrustedProxyList() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("nas.security.trusted-proxies", "127.0.0.1,10.0.0.0/8")
                .withProperty("nas.security.max-concurrent-sessions", "4")
                .withProperty("nas.security.session-idle-timeout-minutes", "90");

        NasProperties properties = Binder.get(environment)
                .bind("nas", NasProperties.class)
                .orElseGet(NasProperties::new);

        assertThat(properties.getSecurity().getTrustedProxies())
                .isEqualTo(List.of("127.0.0.1", "10.0.0.0/8"));
        assertThat(properties.getSecurity().getMaxConcurrentSessions()).isEqualTo(4);
        assertThat(properties.getSecurity().getSessionIdleTimeoutMinutes()).isEqualTo(90);
    }

    @Test
    void bindsOperationalSettings() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("nas.server.public-base-url", "https://nas.example.com/")
                .withProperty("nas.share.default-expiration-days", "7")
                .withProperty("nas.share.max-expiration-days", "30")
                .withProperty("nas.share.custom-token-min-length", "4")
                .withProperty("nas.share.direct-download-link-enabled", "false")
                .withProperty("nas.upload.temp-retention-minutes", "45")
                .withProperty("nas.activity-log.max-archive-files", "12")
                .withProperty("nas.activity-log.page-size-options", "25,50,100")
                .withProperty("nas.tasks.worker-threads", "3")
                .withProperty("nas.metadata-inspector.max-issues-per-area", "250")
                .withProperty("nas.outbound.initial-route", "vpn-required")
                .withProperty("nas.outbound.vpn.enabled", "true")
                .withProperty("nas.outbound.vpn.proxy-host", "gluetun")
                .withProperty("nas.outbound.vpn.proxy-port", "8889")
                .withProperty("nas.outbound.vpn.health-connect-timeout-ms", "900")
                .withProperty("nas.outbound.vpn.tunnel-health-url", "http://gluetun:9999/")
                .withProperty("nas.outbound.vpn.health-request-timeout-ms", "1200")
                .withProperty("nas.outbound.vpn.health-check-interval-ms", "45000");

        NasProperties properties = Binder.get(environment)
                .bind("nas", NasProperties.class)
                .orElseGet(NasProperties::new);

        assertThat(properties.getServer().getPublicBaseUrl()).isEqualTo("https://nas.example.com/");
        assertThat(properties.getShare().getDefaultExpirationDays()).isEqualTo(7);
        assertThat(properties.getShare().getMaxExpirationDays()).isEqualTo(30);
        assertThat(properties.getShare().getCustomTokenMinLength()).isEqualTo(4);
        assertThat(properties.getShare().isDirectDownloadLinkEnabled()).isFalse();
        assertThat(properties.getUpload().getTempRetentionMinutes()).isEqualTo(45);
        assertThat(properties.getActivityLog().getMaxArchiveFiles()).isEqualTo(12);
        assertThat(properties.getActivityLog().getPageSizeOptions()).isEqualTo(List.of(25, 50, 100));
        assertThat(properties.getTasks().getWorkerThreads()).isEqualTo(3);
        assertThat(properties.getMetadataInspector().getMaxIssuesPerArea()).isEqualTo(250);
        assertThat(properties.getOutbound().getInitialRoute()).isEqualTo(NetworkRoute.VPN_REQUIRED);
        assertThat(properties.getOutbound().getVpn().isEnabled()).isTrue();
        assertThat(properties.getOutbound().getVpn().getProxyHost()).isEqualTo("gluetun");
        assertThat(properties.getOutbound().getVpn().getProxyPort()).isEqualTo(8889);
        assertThat(properties.getOutbound().getVpn().getHealthConnectTimeoutMs()).isEqualTo(900);
        assertThat(properties.getOutbound().getVpn().getTunnelHealthUrl())
                .isEqualTo("http://gluetun:9999/");
        assertThat(properties.getOutbound().getVpn().getHealthRequestTimeoutMs()).isEqualTo(1200);
        assertThat(properties.getOutbound().getVpn().getHealthCheckIntervalMs()).isEqualTo(45000L);
    }
}
