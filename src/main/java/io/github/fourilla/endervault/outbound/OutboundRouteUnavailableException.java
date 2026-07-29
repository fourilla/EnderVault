package io.github.fourilla.endervault.outbound;

public class OutboundRouteUnavailableException extends RuntimeException {

    public OutboundRouteUnavailableException(NetworkRoute route) {
        this(route, "");
    }

    public OutboundRouteUnavailableException(NetworkRoute route, String detail) {
        super("Outbound network route is not available: " + route
                + (detail == null || detail.isBlank() ? "" : " (" + detail + ")"));
    }
}
