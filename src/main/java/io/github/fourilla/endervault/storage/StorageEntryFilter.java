package io.github.fourilla.endervault.storage;

public enum StorageEntryFilter {
    ALL,
    DIRECTORIES,
    FILES;

    boolean includes(boolean directory) {
        return this == ALL || (this == DIRECTORIES && directory) || (this == FILES && !directory);
    }
}
