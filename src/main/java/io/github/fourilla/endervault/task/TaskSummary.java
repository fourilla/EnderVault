package io.github.fourilla.endervault.task;

public record TaskSummary(
        long total,
        long running,
        long complete,
        long failed
) {
}
