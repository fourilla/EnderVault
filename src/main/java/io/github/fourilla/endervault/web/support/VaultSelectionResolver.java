package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class VaultSelectionResolver {

    private static final String PATHS_PARAMETER = "paths";

    private final StorageService storageService;

    public VaultSelectionResolver(StorageService storageService) {
        this.storageService = storageService;
    }

    public List<FileItem> resolve(HttpServletRequest request, String directoryPath) throws IOException {
        List<String> paths = parameterValues(request, PATHS_PARAMETER);
        List<String> itemNames = SelectedItems.from(request);
        if (!paths.isEmpty() && !itemNames.isEmpty()) {
            throw new StorageAccessException("Use either full paths or directory item names, not both.");
        }

        List<FileItem> resolved = !paths.isEmpty()
                ? resolvePaths(paths)
                : resolveChildren(directoryPath, itemNames);
        return collapse(resolved);
    }

    private List<FileItem> resolvePaths(List<String> paths) throws IOException {
        return paths.stream()
                .map(this::describePath)
                .toList();
    }

    private List<FileItem> resolveChildren(String directoryPath, List<String> itemNames) throws IOException {
        return itemNames.stream()
                .map(name -> describeChild(directoryPath, name))
                .toList();
    }

    private List<FileItem> collapse(List<FileItem> items) {
        LinkedHashMap<String, FileItem> selected = new LinkedHashMap<>();
        for (FileItem item : items) {
            if (coveredBySelectedDirectory(selected, item.path()) || selected.containsKey(item.path())) {
                continue;
            }
            if (item.directory()) {
                selected.entrySet().removeIf(entry -> isDescendant(item.path(), entry.getKey()));
            }
            selected.put(item.path(), item);
        }
        return List.copyOf(selected.values());
    }

    private boolean coveredBySelectedDirectory(LinkedHashMap<String, FileItem> selected, String path) {
        return selected.values().stream()
                .anyMatch(item -> item.directory() && isDescendant(item.path(), path));
    }

    private boolean isDescendant(String parentPath, String childPath) {
        return childPath.startsWith(parentPath + "/");
    }

    private FileItem describePath(String path) {
        try {
            return storageService.describeVaultPath(path);
        } catch (IOException ex) {
            throw new StorageAccessException("A selected item is no longer available: " + path, ex);
        }
    }

    private FileItem describeChild(String directoryPath, String itemName) {
        try {
            return storageService.describeVaultChild(directoryPath, itemName);
        } catch (IOException ex) {
            throw new StorageAccessException("A selected item is no longer available: " + itemName, ex);
        }
    }

    private List<String> parameterValues(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null || values.length == 0) {
            return List.of();
        }
        return Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }
}
