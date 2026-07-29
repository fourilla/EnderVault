package io.github.fourilla.endervault.outbound.vpn;

import io.github.fourilla.endervault.config.NasProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class VpnProxyHealthService {

    private final NasProperties nasProperties;
    private final AtomicReference<VpnProxyHealth> current = new AtomicReference<>();

    public VpnProxyHealthService(NasProperties nasProperties) {
        this.nasProperties = nasProperties;
    }

    @PostConstruct
    void initialize() {
        refresh();
    }

    public VpnProxyHealth current() {
        VpnProxyHealth health = current.get();
        return health == null || configurationChanged(health) ? refresh() : health;
    }

    @Scheduled(
            fixedDelayString = "${nas.outbound.vpn.health-check-interval-ms:30000}",
            initialDelayString = "${nas.outbound.vpn.health-check-interval-ms:30000}"
    )
    void scheduledRefresh() {
        refresh();
    }

    public synchronized VpnProxyHealth refresh() {
        NasProperties.Vpn settings = nasProperties.getOutbound().getVpn();
        String proxyHost = settings.getProxyHost();
        int proxyPort = settings.getProxyPort();

        if (!settings.isEnabled()) {
            return update(new VpnProxyHealth(
                    VpnProxyHealthState.DISABLED,
                    proxyHost,
                    proxyPort,
                    Instant.now(),
                    -1L,
                    "VPN proxy is disabled."
            ));
        }
        if (proxyHost.isBlank()) {
            return update(new VpnProxyHealth(
                    VpnProxyHealthState.UNCONFIGURED,
                    proxyHost,
                    proxyPort,
                    Instant.now(),
                    -1L,
                    "VPN proxy host is not configured."
            ));
        }

        long startedAt = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(proxyHost, proxyPort),
                    settings.getHealthConnectTimeoutMs()
            );
            return update(new VpnProxyHealth(
                    VpnProxyHealthState.PROXY_REACHABLE,
                    proxyHost,
                    proxyPort,
                    Instant.now(),
                    elapsedMillis(startedAt),
                    "VPN proxy TCP endpoint is reachable."
            ));
        } catch (IOException | RuntimeException ex) {
            return update(new VpnProxyHealth(
                    VpnProxyHealthState.PROXY_UNREACHABLE,
                    proxyHost,
                    proxyPort,
                    Instant.now(),
                    elapsedMillis(startedAt),
                    "VPN proxy TCP endpoint is unreachable (" + ex.getClass().getSimpleName() + ")."
            ));
        }
    }

    private VpnProxyHealth update(VpnProxyHealth health) {
        current.set(health);
        return health;
    }

    private boolean configurationChanged(VpnProxyHealth health) {
        NasProperties.Vpn settings = nasProperties.getOutbound().getVpn();
        if (!Objects.equals(health.proxyHost(), settings.getProxyHost())
                || health.proxyPort() != settings.getProxyPort()) {
            return true;
        }
        return settings.isEnabled()
                ? health.state() == VpnProxyHealthState.DISABLED
                : health.state() != VpnProxyHealthState.DISABLED;
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
