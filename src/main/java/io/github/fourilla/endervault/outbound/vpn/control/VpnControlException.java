package io.github.fourilla.endervault.outbound.vpn.control;

public class VpnControlException extends Exception {

    public VpnControlException(String message) {
        super(message);
    }

    public VpnControlException(String message, Throwable cause) {
        super(message, cause);
    }
}
