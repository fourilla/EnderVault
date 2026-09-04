package io.github.fourilla.endervault.filecommit;

import java.util.Objects;

public record FileCommitJournalEntry(FileCommitManifest manifest, FileCommitJournalState state) {

    public FileCommitJournalEntry {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(state, "state");
        if (!manifest.operationId().equals(state.operationId())) {
            throw new IllegalArgumentException("File commit manifest and state operation ids do not match.");
        }
        if (state.nextItemIndex() > manifest.items().size()) {
            throw new IllegalArgumentException("File commit state points beyond the commit plan.");
        }
    }
}
