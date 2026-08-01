package io.github.fourilla.endervault.outbound.vpn.control;

public enum VpnControlState {
    DISABLED,
    UNCONFIGURED,
    RUNNING,
    STOPPED,
    UNAVAILABLE;

    public boolean controllable() {
        return this == RUNNING || this == STOPPED;
    }

    public boolean running() {
        return this == RUNNING;
    }
}
