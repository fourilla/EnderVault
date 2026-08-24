package io.github.fourilla.endervault.filerequest;

import io.github.fourilla.endervault.common.StorageAccessException;

public class FileRequestDependencyException extends StorageAccessException {

    public FileRequestDependencyException(String message) {
        super(message);
    }
}
