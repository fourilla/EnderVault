package io.github.fourilla.endervault.task;

public class TaskContext {

    private final AppTask task;

    TaskContext(AppTask task) {
        this.task = task;
    }

    public void setTotalBytes(long totalBytes) {
        task.setTotalBytes(totalBytes);
    }

    public void addProcessedBytes(long bytes) {
        task.addProcessedBytes(bytes);
    }

    public void setTotalItems(long totalItems) {
        task.setTotalItems(totalItems);
    }

    public void incrementProcessedItems() {
        task.incrementProcessedItems();
    }

    public void message(String message) {
        task.setMessage(message);
    }

    public void checkCanceled() {
        if (task.cancelRequested() || Thread.currentThread().isInterrupted()) {
            throw new TaskCanceledException();
        }
    }
}
