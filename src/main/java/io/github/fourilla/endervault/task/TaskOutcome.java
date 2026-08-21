package io.github.fourilla.endervault.task;

public record TaskOutcome(
        TaskStatus status,
        String message
) {

    public static TaskOutcome complete(String message) {
        return new TaskOutcome(TaskStatus.COMPLETE, message);
    }

    public static TaskOutcome pending(String message) {
        return new TaskOutcome(TaskStatus.PENDING, message);
    }

    public static TaskOutcome partial(String message) {
        return new TaskOutcome(TaskStatus.PARTIAL, message);
    }
}
