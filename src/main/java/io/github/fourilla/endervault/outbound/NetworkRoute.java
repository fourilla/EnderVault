package io.github.fourilla.endervault.outbound;

public enum NetworkRoute {
    DIRECT("direct", "Direct connection"),
    VPN_REQUIRED("vpn-required", "VPN required");

    private final String settingValue;
    private final String label;

    NetworkRoute(String settingValue, String label) {
        this.settingValue = settingValue;
        this.label = label;
    }

    public String settingValue() {
        return settingValue;
    }

    public String label() {
        return label;
    }

    public static NetworkRoute fromSetting(String value) {
        String normalized = value == null ? "" : value.trim();
        for (NetworkRoute route : values()) {
            if (route.settingValue.equalsIgnoreCase(normalized)
                    || route.name().equalsIgnoreCase(normalized.replace('-', '_'))) {
                return route;
            }
        }
        throw new IllegalArgumentException("Network route is invalid.");
    }
}
