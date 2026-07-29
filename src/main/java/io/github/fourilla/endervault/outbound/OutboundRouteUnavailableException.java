package io.github.fourilla.endervault.outbound;

public class OutboundRouteUnavailableException extends RuntimeException {

    public OutboundRouteUnavailableException(NetworkRoute route) {
        super("Outbound network route is not available: " + route);
    }
}
