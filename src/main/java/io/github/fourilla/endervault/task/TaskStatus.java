package io.github.fourilla.endervault.task;

public enum TaskStatus {
    QUEUED("Queued", true),
    RUNNING("Running", true),
    PENDING("Needs review", false),
    COMPLETE("Complete", false),
    PARTIAL("Partial", false),
    FAILED("Failed", false),
    CANCELED("Canceled", false);

    private final String label;
    private final boolean active;

    TaskStatus(String label, boolean active) {
        this.label = label;
        this.active = active;
    }

    public String label() {
        return label;
    }

    public boolean active() {
        return active;
    }
}
