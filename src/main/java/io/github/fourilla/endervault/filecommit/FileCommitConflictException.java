package io.github.fourilla.endervault.filecommit;

import java.nio.file.FileAlreadyExistsException;

public class FileCommitConflictException extends FileAlreadyExistsException {

    private final String operationId;

    public FileCommitConflictException(String file, String operationId) {
        super(file);
        this.operationId = FileCommitJournalPaths.requireOperationId(operationId);
    }

    public String operationId() {
        return operationId;
    }
}
