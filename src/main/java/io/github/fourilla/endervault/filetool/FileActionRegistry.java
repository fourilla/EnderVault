package io.github.fourilla.endervault.filetool;

import io.github.fourilla.endervault.recent.RecentListItem;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.FileItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class FileActionRegistry {

    private final FileToolRegistry fileToolRegistry;

    public FileActionRegistry() {
        this(new FileToolRegistry());
    }

    @Autowired
    public FileActionRegistry(FileToolRegistry fileToolRegistry) {
        this.fileToolRegistry = fileToolRegistry;
    }

    public FileToolDescriptor resolve(FileDetail detail) {
        return resolve(detail.name(), detail.directory(), detail.mediaType(), detail.extension());
    }

    public FileToolDescriptor resolve(FileItem item) {
        return resolve(item.name(), item.directory(), item.mediaType(), extension(item.name()));
    }

    public FileToolDescriptor resolve(RecentListItem item) {
        return resolve(item.name(), item.directory(), item.mediaType(), extension(item.name()));
    }

    public FileToolDescriptor resolve(String name, boolean directory, String mediaType, String extension) {
        return fileToolRegistry.resolve(context(name, directory, mediaType, extension));
    }

    public FileToolType typeFor(FileDetail detail) {
        return typeFor(detail.name(), detail.directory(), detail.mediaType(), detail.extension());
    }

    public FileToolType typeFor(FileItem item) {
        return typeFor(item.name(), item.directory(), item.mediaType(), extension(item.name()));
    }

    public FileToolType typeFor(RecentListItem item) {
        return typeFor(item.name(), item.directory(), item.mediaType(), extension(item.name()));
    }

    public FileToolType typeFor(String name, boolean directory, String mediaType, String extension) {
        return resolve(name, directory, mediaType, extension).type();
    }

    public boolean previewPageAvailable(FileDetail detail) {
        return resolve(detail).previewPageAvailable();
    }

    public boolean previewPageAvailable(FileItem item) {
        return resolve(item).previewPageAvailable();
    }

    public boolean previewPageAvailable(RecentListItem item) {
        return resolve(item).previewPageAvailable();
    }

    public boolean previewPageAvailable(String name, boolean directory, String mediaType, String extension) {
        return resolve(name, directory, mediaType, extension).previewPageAvailable();
    }

    public List<FileActionKind> browserActions(FileItem item) {
        return browserActions(item.name(), item.directory(), item.mediaType(), extension(item.name()));
    }

    public List<FileActionKind> browserActions(RecentListItem item) {
        return browserActions(item.name(), item.directory(), item.mediaType(), extension(item.name()));
    }

    public List<FileActionKind> browserActions(FileDetail detail) {
        return browserActions(detail.name(), detail.directory(), detail.mediaType(), detail.extension());
    }

    public List<FileActionKind> browserActions(String name, boolean directory, String mediaType, String extension) {
        if (directory) {
            return List.of(FileActionKind.DETAILS);
        }

        List<FileActionKind> actions = new ArrayList<>();
        actions.add(FileActionKind.DOWNLOAD);
        if (previewPageAvailable(name, false, mediaType, extension)) {
            actions.add(FileActionKind.PREVIEW);
        }
        return List.copyOf(actions);
    }

    public List<FileActionKind> sharedDirectoryActions(FileItem item) {
        if (item.directory()) {
            return List.of();
        }
        return browserActions(item);
    }

    public String extension(String name) {
        if (name == null) {
            return "";
        }
        int index = name.lastIndexOf('.');
        if (index <= 0 || index == name.length() - 1) {
            return "";
        }
        return name.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizedExtension(String name, String extension) {
        if (extension != null && !extension.isBlank()) {
            return extension.toLowerCase(Locale.ROOT);
        }
        return extension(name);
    }

    private FileToolContext context(String name, boolean directory, String mediaType, String extension) {
        return new FileToolContext(name, directory, mediaType, normalizedExtension(name, extension));
    }
}
