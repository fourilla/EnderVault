package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.filetool.FileActionKind;

public record FileActionLink(
        FileActionKind kind,
        String id,
        String label,
        String icon,
        String href,
        boolean newTab
) {
    public String target() {
        return newTab ? "_blank" : null;
    }

    public String rel() {
        return newTab ? "noopener noreferrer" : null;
    }
}
