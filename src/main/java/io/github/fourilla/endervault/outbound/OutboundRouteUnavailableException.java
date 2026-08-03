package io.github.fourilla.endervault.outbound;

import io.github.fourilla.endervault.common.StorageAccessException;

public class OutboundRouteUnavailableException extends StorageAccessException {

    public OutboundRouteUnavailableException(NetworkRoute route) {
        this(route, "");
    }

    public OutboundRouteUnavailableException(NetworkRoute route, String detail) {
        super("Outbound network route is not available: " + route.label()
                + (detail == null || detail.isBlank() ? "" : " (" + detail + ")"));
    }
}
