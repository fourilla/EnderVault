package io.github.fourilla.endervault.outbound.vpn.control;

import io.github.fourilla.endervault.config.NasProperties;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class VpnControlService {

    private final NasProperties nasProperties;
    private final GluetunControlClient controlClient;
    private final AtomicReference<VpnControlStatus> current = new AtomicReference<>();

    public VpnControlService(NasProperties nasProperties, GluetunControlClient controlClient) {
        this.nasProperties = nasProperties;
        this.controlClient = controlClient;
    }

    @PostConstruct
    void initialize() {
        refresh();
    }

    public VpnControlStatus current() {
        VpnControlStatus status = current.get();
        return status == null ? refresh() : status;
    }

    @Scheduled(
            fixedDelayString = "${nas.outbound.vpn.health-check-interval-ms:30000}",
            initialDelayString = "5000"
    )
    void scheduledRefresh() {
        refresh();
    }

    public synchronized VpnControlStatus refresh() {
        long startedAt = System.nanoTime();
        NasProperties.Vpn vpn = nasProperties.getOutbound().getVpn();
        if (!vpn.isEnabled()) {
            return update(VpnControlState.DISABLED, "", startedAt, "VPN egress is disabled.");
        }
        if (!controlClient.configured()) {
            return update(
                    VpnControlState.UNCONFIGURED,
                    "",
                    startedAt,
                    "Gluetun Control API is not configured."
            );
        }

        try {
            GluetunControlClient.Inspection inspection = controlClient.inspect();
            return update(inspection.state(), inspection.publicIp(), startedAt, inspection.detail());
        } catch (VpnControlException ex) {
            return update(VpnControlState.UNAVAILABLE, "", startedAt, ex.getMessage());
        }
    }

    public synchronized VpnControlStatus connect() throws VpnControlException {
        requireControllableConfiguration();
        controlClient.setRunning(true);
        return refresh();
    }

    public synchronized VpnControlStatus disconnect() throws VpnControlException {
        requireControllableConfiguration();
        controlClient.setRunning(false);
        return refresh();
    }

    public synchronized VpnControlStatus reconnect() throws VpnControlException {
        requireControllableConfiguration();
        controlClient.setRunning(false);
        controlClient.setRunning(true);
        return refresh();
    }

    private void requireControllableConfiguration() throws VpnControlException {
        if (!nasProperties.getOutbound().getVpn().isEnabled()) {
            throw new VpnControlException("VPN egress is disabled in settings.");
        }
        if (!controlClient.configured()) {
            throw new VpnControlException("Gluetun Control API is not configured.");
        }
    }

    private VpnControlStatus update(
            VpnControlState state,
            String publicIp,
            long startedAt,
            String detail
    ) {
        VpnControlStatus status = new VpnControlStatus(
                state,
                publicIp,
                Instant.now(),
                Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L),
                detail
        );
        current.set(status);
        return status;
    }
}
