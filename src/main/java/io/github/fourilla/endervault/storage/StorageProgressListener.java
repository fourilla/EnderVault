package io.github.fourilla.endervault.storage;

public interface StorageProgressListener {

    StorageProgressListener NOOP = new StorageProgressListener() {
    };

    default void checkCanceled() {
    }

    default void onBytesProcessed(long bytes) {
    }

    default void onItemProcessed() {
    }

    /** Phase-local work, with optional byte progress for the current item. */
    default void onProgress(String phase, long completed, long total, long currentBytes, long currentTotal) {
    }

    /** The durable finalization boundary has been crossed; user cancellation is no longer accepted. */
    default void onFinalizing() {
    }
}
