package io.github.fourilla.endervault.transfer;

import java.io.Serializable;
import java.util.List;

public record TransferBuffer(
        List<TransferBufferItem> items
) implements Serializable {

    public static TransferBuffer empty() {
        return new TransferBuffer(List.of());
    }

    public TransferBuffer {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean active() {
        return !items.isEmpty();
    }

    public int count() {
        return items.size();
    }
}
