package io.github.fourilla.endervault.filetool.archive;

import io.github.fourilla.endervault.task.TaskContext;

interface ArchiveExtractionProgress {

    ArchiveExtractionProgress NOOP = new ArchiveExtractionProgress() {
    };

    default void setTotalBytes(long totalBytes) {
    }

    default void setTotalItems(long totalItems) {
    }

    default void addProcessedBytes(long bytes) {
    }

    default void incrementProcessedItems() {
    }

    default void message(String message) {
    }

    default void checkCanceled() {
    }

    static ArchiveExtractionProgress task(TaskContext context) {
        return new ArchiveExtractionProgress() {
            @Override
            public void setTotalBytes(long totalBytes) {
                context.setTotalBytes(totalBytes);
            }

            @Override
            public void setTotalItems(long totalItems) {
                context.setTotalItems(totalItems);
            }

            @Override
            public void addProcessedBytes(long bytes) {
                context.addProcessedBytes(bytes);
            }

            @Override
            public void incrementProcessedItems() {
                context.incrementProcessedItems();
            }

            @Override
            public void message(String message) {
                context.message(message);
            }

            @Override
            public void checkCanceled() {
                context.checkCanceled();
            }
        };
    }
}
