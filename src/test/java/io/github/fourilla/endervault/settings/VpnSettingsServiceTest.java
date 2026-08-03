package io.github.fourilla.endervault.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.config.LocalPropertiesFile;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthService;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthState;
import io.github.fourilla.endervault.outbound.vpn.VpnTunnelHealthProbe;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

class VpnSettingsServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void savesVpnSettingsAndRefreshesRuntimeHealth() throws Exception {
        Path configFile = tempDir.resolve("endervault-nas.properties");
        Files.writeString(configFile, "nas.setup.accepted=true\n", StandardCharsets.UTF_8);
        NasProperties properties = new NasProperties();
        VpnSettingsService service = service(properties, configFile);
        MultiValueMap<String, String> parameters = validParameters();
        parameters.set("proxyHost", "vpn");
        parameters.set("proxyPort", "8899");
        parameters.set("tunnelHealthUrl", "http://vpn:9999/");
        parameters.set("healthCheckIntervalMs", "45000");

        var health = service.save(service.updateFrom(parameters));

        String saved = Files.readString(configFile, StandardCharsets.UTF_8);
        assertThat(saved)
                .contains("nas.outbound.vpn.enabled=false")
                .contains("nas.outbound.vpn.proxy-host=vpn")
                .contains("nas.outbound.vpn.proxy-port=8899")
                .contains("nas.outbound.vpn.tunnel-health-url=http://vpn:9999/")
                .contains("nas.outbound.vpn.health-check-interval-ms=45000");
        assertThat(properties.getOutbound().getVpn().getProxyHost()).isEqualTo("vpn");
        assertThat(properties.getOutbound().getVpn().getProxyPort()).isEqualTo(8899);
        assertThat(properties.getOutbound().getVpn().getHealthCheckIntervalMs()).isEqualTo(45000L);
        assertThat(health.state()).isEqualTo(VpnProxyHealthState.DISABLED);
    }

    @Test
    void requiresProxyHostWhenVpnEgressIsEnabled() {
        VpnSettingsService service = service(new NasProperties(), tempDir.resolve("missing.properties"));
        MultiValueMap<String, String> parameters = validParameters();
        parameters.add("enabled", "on");

        assertThatThrownBy(() -> service.updateFrom(parameters))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Proxy host is required");
    }

    @Test
    void rejectsTunnelHealthUrlWithCredentials() {
        VpnSettingsService service = service(new NasProperties(), tempDir.resolve("missing.properties"));
        MultiValueMap<String, String> parameters = validParameters();
        parameters.set("tunnelHealthUrl", "http://user:secret@vpn:9999/");

        assertThatThrownBy(() -> service.updateFrom(parameters))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("without credentials");
    }

    private VpnSettingsService service(NasProperties properties, Path configFile) {
        return new VpnSettingsService(
                properties,
                new LocalPropertiesFile(configFile),
                new VpnProxyHealthService(properties, new VpnTunnelHealthProbe())
        );
    }

    private MultiValueMap<String, String> validParameters() {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("proxyHost", "");
        parameters.add("proxyPort", "8888");
        parameters.add("healthConnectTimeoutMs", "1500");
        parameters.add("tunnelHealthUrl", "");
        parameters.add("healthRequestTimeoutMs", "3000");
        parameters.add("healthCheckIntervalMs", "30000");
        return parameters;
    }
}
