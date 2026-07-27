package io.github.fourilla.endervault.filetool.text;

import io.github.fourilla.endervault.storage.FileItem;

public record TextDraftRecoveryResult(
        FileItem file,
        boolean originalParentMissing
) {
}
