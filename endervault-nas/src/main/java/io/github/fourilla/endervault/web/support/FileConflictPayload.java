package io.github.fourilla.endervault.web.support;

public record FileConflictPayload(
        String operation,
        String itemName,
        String targetPath,
        String defaultPolicy,
        String message
) {
}
