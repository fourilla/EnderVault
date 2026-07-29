package io.github.fourilla.endervault.web.settings;

import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.VpnStatusView;

public record VpnSettingsResponse(
        boolean ok,
        FlashNotification notification,
        VpnStatusView vpn
) {

    public static VpnSettingsResponse ok(FlashNotification notification, VpnStatusView vpn) {
        return new VpnSettingsResponse(true, notification, vpn);
    }
}
