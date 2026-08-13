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

    public void targetPath(String targetPath) {
        task.setTargetPath(targetPath);
    }

    public boolean canceled() {
        return task.cancelRequested() || Thread.currentThread().isInterrupted();
    }

    public void checkCanceled() {
        if (canceled()) {
            throw new TaskCanceledException();
        }
    }
}
