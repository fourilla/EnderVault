package io.github.fourilla.endervault.upload;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.storage.StorageService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

public record DirectoryUploadManifest(String name, List<File> files, List<String> directories, String resumeId) {
    public static final int MAX_ENTRIES = io.github.fourilla.endervault.config.NasProperties.Upload.DIRECTORY_ENTRIES_CEILING;
    public record File(String path, long size, long lastModified) {}

    DirectoryUploadManifest validate(StorageService storage) {
        storage.validateVaultEntryName(name);
        if (files == null || directories == null || 1L + files.size() + directories.size() > MAX_ENTRIES) {
            throw new StorageAccessException("A directory upload supports at most 10,000 entries.");
        }
        Map<String, String> names = new LinkedHashMap<>();
        TreeSet<String> dirs = new TreeSet<>();
        for (String dir : directories) {
            validatePath(storage, dir);
            addParents(dir + "/child", dirs, names);
        }
        List<File> checkedFiles = new ArrayList<>();
        long total = 0;
        for (File file : files) {
            if (file == null || file.size() < 0 || file.lastModified() < 0) throw new StorageAccessException("Invalid directory file metadata.");
            validatePath(storage, file.path());
            addParents(file.path(), dirs, names);
            String key = file.path().toLowerCase(Locale.ROOT);
            if (names.putIfAbsent(key, file.path()) != null) throw new StorageAccessException("Duplicate or conflicting directory entries.");
            try { total = Math.addExact(total, file.size()); }
            catch (ArithmeticException ex) { throw new StorageAccessException("Directory upload is too large."); }
            checkedFiles.add(file);
        }
        if (1L + dirs.size() + files.size() > MAX_ENTRIES) throw new StorageAccessException("Too many directory entries.");
        checkedFiles.sort(java.util.Comparator.comparing(File::path));
        return new DirectoryUploadManifest(name, List.copyOf(checkedFiles), List.copyOf(dirs), resumeId);
    }

    private static void addParents(String path, TreeSet<String> dirs, Map<String, String> names) {
        int separator = path.indexOf('/');
        while (separator >= 0) {
            String parent = path.substring(0, separator);
            String existing = names.putIfAbsent(parent.toLowerCase(Locale.ROOT), parent);
            if (existing != null && (!existing.equals(parent) || !dirs.contains(parent))) {
                throw new StorageAccessException("Conflicting directory entry names.");
            }
            dirs.add(parent);
            separator = path.indexOf('/', separator + 1);
        }
    }

    static void validatePath(StorageService storage, String path) {
        if (path == null || path.isBlank() || path.length() > 1024 || path.contains("\\")) {
            throw new StorageAccessException("Invalid directory upload path.");
        }
        String[] parts = path.split("/", -1);
        if (parts.length > io.github.fourilla.endervault.config.NasProperties.Upload.DIRECTORY_DEPTH_CEILING) throw new StorageAccessException("Directory upload is too deeply nested.");
        for (String part : parts) storage.validateVaultEntryName(part);
    }
}
