package io.github.fourilla.endervault.web.support;

public record OutboundRouteResponse(
        boolean ok,
        FlashNotification notification,
        OutboundRouteView route
) {

    public static OutboundRouteResponse ok(FlashNotification notification, OutboundRouteView route) {
        return new OutboundRouteResponse(true, notification, route);
    }
}
