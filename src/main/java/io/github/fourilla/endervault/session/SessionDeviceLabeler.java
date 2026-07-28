package io.github.fourilla.endervault.session;

import java.util.ArrayList;
import java.util.List;

final class SessionDeviceLabeler {

    private SessionDeviceLabeler() {
    }

    static String label(String userAgent) {
        String value = userAgent == null ? "" : userAgent;
        List<String> parts = new ArrayList<>(2);
        parts.add(browser(value));
        parts.add(operatingSystem(value));
        return String.join(" on ", parts);
    }

    private static String browser(String userAgent) {
        if (userAgent.contains("Edg/")) {
            return "Edge";
        }
        if (userAgent.contains("OPR/") || userAgent.contains("Opera/")) {
            return "Opera";
        }
        if (userAgent.contains("Firefox/")) {
            return "Firefox";
        }
        if (userAgent.contains("Chrome/") || userAgent.contains("CriOS/")) {
            return "Chrome";
        }
        if (userAgent.contains("Safari/")) {
            return "Safari";
        }
        return "Unknown browser";
    }

    private static String operatingSystem(String userAgent) {
        if (userAgent.contains("Android")) {
            return "Android";
        }
        if (userAgent.contains("iPhone") || userAgent.contains("iPad")) {
            return "iOS";
        }
        if (userAgent.contains("Windows")) {
            return "Windows";
        }
        if (userAgent.contains("Mac OS X") || userAgent.contains("Macintosh")) {
            return "macOS";
        }
        if (userAgent.contains("Linux")) {
            return "Linux";
        }
        return "Unknown OS";
    }
}
