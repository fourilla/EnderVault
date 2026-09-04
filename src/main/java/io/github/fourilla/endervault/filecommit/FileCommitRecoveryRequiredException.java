package io.github.fourilla.endervault.filecommit;

import java.io.IOException;

public class FileCommitRecoveryRequiredException extends IOException {

    public FileCommitRecoveryRequiredException(String message) {
        super(message);
    }
}
