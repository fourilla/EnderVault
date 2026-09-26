package io.github.fourilla.endervault.task;

public class TaskContext {

    private final AppTask task;

    TaskContext(AppTask task) {
        this.task = task;
    }

    public String taskId() {
        return task.id();
    }

    public void resultReference(String reference) { task.setResultReference(reference); }

    public void directoryTransferReview(String id) { task.addDirectoryTransferReview(id); }

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

    public void beginFinalization() { task.beginFinalization(); }

    public void progress(String phase, long completed, long total, long bytes, long byteTotal) {
        task.setPhaseProgress(phase, completed, total, bytes, byteTotal);
    }

    public static io.github.fourilla.endervault.storage.StorageProgressListener transferProgress(TaskContext context) {
        return new io.github.fourilla.endervault.storage.StorageProgressListener() {
            @Override public void checkCanceled() { context.checkCanceled(); }
            @Override public void onFinalizing() { context.beginFinalization(); }
            @Override public void onProgress(String phase, long completed, long total, long bytes, long byteTotal) {
                context.progress(phase, completed, total, bytes, byteTotal);
            }
        };
    }
}
