package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.config.NasProperties;

public final class BookmarkLinkClickAction {

    public static final String DETAIL = "detail";
    public static final String OPEN = "open";

    private BookmarkLinkClickAction() {
    }

    public static String from(NasProperties nasProperties) {
        if (nasProperties == null || nasProperties.getBookmarks() == null) {
            return OPEN;
        }
        return normalize(nasProperties.getBookmarks().getLinkClickAction());
    }

    public static String normalize(String value) {
        return DETAIL.equalsIgnoreCase(value) ? DETAIL : OPEN;
    }
}
