package io.github.fourilla.endervault.remote;

import io.github.fourilla.endervault.common.StorageAccessException;

final class RemoteDownloadHttpStatusException extends StorageAccessException {

    private final int status;

    RemoteDownloadHttpStatusException(int status) {
        super("Remote server returned HTTP " + status + ".");
        this.status = status;
    }

    int status() {
        return status;
    }
}
