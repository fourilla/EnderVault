package io.github.fourilla.endervault.storage;

import java.io.IOException;
import java.util.List;

public class PartialStorageCommitException extends IOException {

    private final List<String> remainingVaultPaths;

    public PartialStorageCommitException(List<String> remainingVaultPaths, Throwable cause) {
        super(message(remainingVaultPaths), cause);
        this.remainingVaultPaths = List.copyOf(remainingVaultPaths);
    }

    public List<String> remainingVaultPaths() {
        return remainingVaultPaths;
    }

    private static String message(List<String> paths) {
        List<String> safePaths = paths == null ? List.of() : paths;
        String examples = safePaths.stream().limit(3).map(path -> "/" + path).reduce((a, b) -> a + ", " + b)
                .orElse("unknown paths");
        String suffix = safePaths.size() > 3 ? " and %d more".formatted(safePaths.size() - 3) : "";
        return "Archive extraction partially committed. Manual review is required for " + examples + suffix + ".";
    }
}
