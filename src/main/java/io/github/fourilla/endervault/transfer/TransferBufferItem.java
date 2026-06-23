package io.github.fourilla.endervault.transfer;

import java.io.Serializable;

public record TransferBufferItem(
        String path,
        String name,
        boolean directory
) implements Serializable {

    public String typeLabel() {
        return directory ? "Directory" : "File";
    }

    public String iconClass() {
        return directory ? "fas fa-folder item-icon" : "fas fa-file item-icon";
    }
}
