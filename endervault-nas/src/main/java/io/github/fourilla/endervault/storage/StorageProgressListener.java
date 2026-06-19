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
}
