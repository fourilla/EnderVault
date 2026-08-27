package io.github.fourilla.endervault.settings;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.fourilla.endervault.config.LocalPropertiesFile;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealth;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthService;
import io.github.fourilla.endervault.outbound.vpn.VpnTunnelHealthEndpoint;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

@Service
public class VpnSettingsService {

    private static final int MIN_TIMEOUT_MS = 100;
    private static final int MAX_TIMEOUT_MS = 60000;

    private final NasProperties nasProperties;
    private final LocalPropertiesFile localPropertiesFile;
    private final VpnProxyHealthService vpnProxyHealthService;

    public VpnSettingsService(
            NasProperties nasProperties,
            LocalPropertiesFile localPropertiesFile,
            VpnProxyHealthService vpnProxyHealthService
    ) {
        this.nasProperties = nasProperties;
        this.localPropertiesFile = localPropertiesFile;
        this.vpnProxyHealthService = vpnProxyHealthService;
    }

    public VpnSettingsSnapshot currentSettings() {
        NasProperties.Vpn vpn = nasProperties.getOutbound().getVpn();
        return new VpnSettingsSnapshot(
                nasProperties.getOutbound().getInitialRoute().settingValue(),
                vpn.isEnabled(),
                vpn.getProxyHost(),
                vpn.getProxyPort(),
                vpn.getHealthConnectTimeoutMs(),
                vpn.getTunnelHealthUrl(),
                vpn.getHealthRequestTimeoutMs(),
                vpn.getHealthCheckIntervalMs(),
                localPropertiesFile.configFile().toString()
        );
    }

    public VpnSettingsUpdate updateFrom(MultiValueMap<String, String> parameters) {
        NetworkRoute initialRoute = NetworkRoute.fromSetting(first(parameters, "initialRoute"));
        boolean enabled = parameters.containsKey("enabled");
        String proxyHost = proxyHost(first(parameters, "proxyHost"), enabled);
        int proxyPort = intRange(first(parameters, "proxyPort"), 1, 65535, "Proxy port");
        int healthConnectTimeoutMs = Math.toIntExact(scaledMilliseconds(
                first(parameters, "healthConnectTimeoutSeconds"),
                MIN_TIMEOUT_MS,
                MAX_TIMEOUT_MS,
                "Proxy health connect timeout"
        ));
        String tunnelHealthUrl = tunnelHealthUrl(first(parameters, "tunnelHealthUrl"));
        int healthRequestTimeoutMs = Math.toIntExact(scaledMilliseconds(
                first(parameters, "healthRequestTimeoutSeconds"),
                MIN_TIMEOUT_MS,
                MAX_TIMEOUT_MS,
                "Tunnel health request timeout"
        ));
        long healthCheckIntervalMs = scaledMilliseconds(
                first(parameters, "healthCheckIntervalSeconds"),
                1000L,
                Long.MAX_VALUE,
                "Health check interval"
        );
        return new VpnSettingsUpdate(
                initialRoute,
                enabled,
                proxyHost,
                proxyPort,
                healthConnectTimeoutMs,
                tunnelHealthUrl,
                healthRequestTimeoutMs,
                healthCheckIntervalMs
        );
    }

    public VpnProxyHealth save(VpnSettingsUpdate update) throws IOException {
        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("nas.outbound.initial-route", update.initialRoute().settingValue());
        updates.put("nas.outbound.vpn.enabled", Boolean.toString(update.enabled()));
        updates.put("nas.outbound.vpn.proxy-host", update.proxyHost());
        updates.put("nas.outbound.vpn.proxy-port", Integer.toString(update.proxyPort()));
        updates.put(
                "nas.outbound.vpn.health-connect-timeout-ms",
                Integer.toString(update.healthConnectTimeoutMs())
        );
        updates.put("nas.outbound.vpn.tunnel-health-url", update.tunnelHealthUrl());
        updates.put(
                "nas.outbound.vpn.health-request-timeout-ms",
                Integer.toString(update.healthRequestTimeoutMs())
        );
        updates.put(
                "nas.outbound.vpn.health-check-interval-ms",
                Long.toString(update.healthCheckIntervalMs())
        );
        localPropertiesFile.update(updates, "# VPN egress settings managed from EnderVault Settings.");

        NasProperties.Vpn vpn = nasProperties.getOutbound().getVpn();
        nasProperties.getOutbound().setInitialRoute(update.initialRoute());
        vpn.setEnabled(update.enabled());
        vpn.setProxyHost(update.proxyHost());
        vpn.setProxyPort(update.proxyPort());
        vpn.setHealthConnectTimeoutMs(update.healthConnectTimeoutMs());
        vpn.setTunnelHealthUrl(update.tunnelHealthUrl());
        vpn.setHealthRequestTimeoutMs(update.healthRequestTimeoutMs());
        vpn.setHealthCheckIntervalMs(update.healthCheckIntervalMs());
        return vpnProxyHealthService.refresh();
    }

    public boolean requiresRestart(VpnSettingsUpdate update) {
        return nasProperties.getOutbound().getInitialRoute() != update.initialRoute()
                || nasProperties.getOutbound().getVpn().getHealthCheckIntervalMs() != update.healthCheckIntervalMs();
    }

    private static String proxyHost(String rawValue, boolean required) {
        String value = clean(rawValue);
        if (required && value.isBlank()) {
            throw new IllegalArgumentException("Proxy host is required when VPN egress is enabled.");
        }
        if (!value.isBlank()
                && (value.length() > 253 || !value.matches("[A-Za-z0-9._:\\[\\]-]+"))) {
            throw new IllegalArgumentException("Proxy host is invalid.");
        }
        return value;
    }

    private static String tunnelHealthUrl(String rawValue) {
        try {
            return VpnTunnelHealthEndpoint.normalizeOptional(clean(rawValue));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Tunnel health URL must be an HTTP(S) URL without credentials or a fragment.");
        }
    }

    private static int intRange(String rawValue, int min, int max, String label) {
        return Math.toIntExact(longRange(rawValue, min, max, label));
    }

    private static long longRange(String rawValue, long min, long max, String label) {
        try {
            long value = Long.parseLong(clean(rawValue));
            if (value < min || value > max) {
                throw new IllegalArgumentException(label + " must be between " + min + " and " + max + ".");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be a number.");
        }
    }

    private static long scaledMilliseconds(String rawValue, long min, long max, String label) {
        try {
            long value = new BigDecimal(clean(rawValue))
                    .multiply(BigDecimal.valueOf(1000L))
                    .longValueExact();
            if (value < min || value > max) {
                throw new IllegalArgumentException(label + " is outside the supported range.");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be a number.");
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(label + " has too much precision or is too large.");
        }
    }

    private static String seconds(long milliseconds) {
        return BigDecimal.valueOf(milliseconds)
                .divide(BigDecimal.valueOf(1000L))
                .stripTrailingZeros()
                .toPlainString();
    }

    private static String first(MultiValueMap<String, String> parameters, String key) {
        String value = parameters.getFirst(key);
        return value == null ? "" : value;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace("\r", "").replace("\n", "").trim();
    }

    public record VpnSettingsSnapshot(
            String initialRoute,
            boolean enabled,
            String proxyHost,
            int proxyPort,
            int healthConnectTimeoutMs,
            String tunnelHealthUrl,
            int healthRequestTimeoutMs,
            long healthCheckIntervalMs,
            String configPath
    ) {

        @JsonProperty
        public String healthConnectTimeoutSeconds() {
            return seconds(healthConnectTimeoutMs);
        }

        @JsonProperty
        public String healthRequestTimeoutSeconds() {
            return seconds(healthRequestTimeoutMs);
        }

        @JsonProperty
        public String healthCheckIntervalSeconds() {
            return seconds(healthCheckIntervalMs);
        }
    }

    public record VpnSettingsUpdate(
            NetworkRoute initialRoute,
            boolean enabled,
            String proxyHost,
            int proxyPort,
            int healthConnectTimeoutMs,
            String tunnelHealthUrl,
            int healthRequestTimeoutMs,
            long healthCheckIntervalMs
    ) {
    }
}
