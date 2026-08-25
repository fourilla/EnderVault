package io.github.fourilla.endervault.web.api.v1.vpn;

import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.VpnRuntimeStatusView;

public record VpnControlActionResponse(
        boolean ok,
        FlashNotification notification,
        VpnRuntimeStatusView vpn
) {

    public static VpnControlActionResponse ok(
            FlashNotification notification,
            VpnRuntimeStatusView vpn
    ) {
        return new VpnControlActionResponse(true, notification, vpn);
    }

    public static VpnControlActionResponse error(FlashNotification notification) {
        return new VpnControlActionResponse(false, notification, null);
    }
}
