package io.github.fourilla.endervault.transfer;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.util.Locale;

public enum TransferOperation {
    MOVE("Move", "Moved", "MOVE"),
    COPY("Copy", "Copied", "COPY");

    private final String label;
    private final String completedLabel;
    private final String activityType;

    TransferOperation(String label, String completedLabel, String activityType) {
        this.label = label;
        this.completedLabel = completedLabel;
        this.activityType = activityType;
    }

    public String label() {
        return label;
    }

    public String completedLabel() {
        return completedLabel;
    }

    public String activityType() {
        return activityType;
    }

    public static TransferOperation from(String value) {
        if (value == null || value.isBlank()) {
            throw new StorageAccessException("Transfer operation is required.");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "move" -> MOVE;
            case "copy" -> COPY;
            default -> throw new StorageAccessException("Unsupported transfer operation: " + value);
        };
    }
}
