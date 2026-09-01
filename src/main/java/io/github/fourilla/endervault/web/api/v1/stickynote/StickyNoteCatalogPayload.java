package io.github.fourilla.endervault.web.api.v1.stickynote;

import java.util.List;

public record StickyNoteCatalogPayload(List<StickyNoteCatalogItemPayload> notes) {

    public record StickyNoteCatalogItemPayload(
            String id,
            String content,
            String summary,
            String contextLabel,
            String targetType,
            String surfaceLabel,
            String updatedLabel,
            boolean targetExists,
            String openUrl
    ) {
    }
}
