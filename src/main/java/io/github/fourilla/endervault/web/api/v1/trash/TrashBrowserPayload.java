package io.github.fourilla.endervault.web.api.v1.trash;

import io.github.fourilla.endervault.trash.TrashRecord;
import java.util.List;

public record TrashBrowserPayload(List<TrashItemPayload> items) {

    public TrashBrowserPayload {
        items = List.copyOf(items);
    }

    public static TrashBrowserPayload from(List<TrashRecord> records) {
        return new TrashBrowserPayload(records.stream()
                .map(TrashItemPayload::from)
                .toList());
    }

    public record TrashItemPayload(
            String id,
            String originalName,
            String originalPath,
            boolean directory,
            String typeLabel,
            String sizeLabel,
            String deletedLabel,
            String expiresLabel
    ) {

        static TrashItemPayload from(TrashRecord record) {
            return new TrashItemPayload(
                    record.id(),
                    record.originalName(),
                    record.originalPath(),
                    record.directory(),
                    record.typeLabel(),
                    record.sizeLabel(),
                    record.deletedLabel(),
                    record.expiresLabel()
            );
        }
    }
}
