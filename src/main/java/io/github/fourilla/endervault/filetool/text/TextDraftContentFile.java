package io.github.fourilla.endervault.filetool.text;

import java.time.Instant;

public record TextDraftContentFile(
        String fileName,
        long size,
        Instant modifiedAt
) {
}
