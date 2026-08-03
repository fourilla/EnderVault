package io.github.fourilla.endervault.outbound.vpn.control;

import java.time.Instant;

public record VpnControlStatus(
        VpnControlState state,
        String publicIp,
        Instant checkedAt,
        long latencyMillis,
        String detail
) {

    public boolean controllable() {
        return state.controllable();
    }

    public boolean running() {
        return state.running();
    }
}
