package io.github.fourilla.endervault.web.api.v1.outbound;

import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.OutboundRouteView;

public record OutboundRouteResponse(
        boolean ok,
        FlashNotification notification,
        OutboundRouteView route
) {

    public static OutboundRouteResponse ok(FlashNotification notification, OutboundRouteView route) {
        return new OutboundRouteResponse(true, notification, route);
    }

    public static OutboundRouteResponse error(String message) {
        return new OutboundRouteResponse(false, FlashNotification.error(message), null);
    }
}
