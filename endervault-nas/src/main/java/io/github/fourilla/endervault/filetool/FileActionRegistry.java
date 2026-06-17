package io.github.fourilla.endervault.filetool;

import io.github.fourilla.endervault.recent.RecentListItem;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.FileItem;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class FileActionRegistry {

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "text", "md", "markdown", "log",
            "csv", "tsv", "json", "jsonl", "xml", "html", "htm", "css",
            "js", "mjs", "cjs", "ts", "tsx", "jsx",
            "java", "c", "h", "cpp", "hpp", "cc", "cs",
            "py", "rb", "go", "rs", "php",
            "sh", "bash", "zsh", "bat", "cmd", "ps1",
            "sql", "properties", "conf", "cfg", "ini", "yml", "yaml", "toml"
    );
    private static final Set<String> TEXT_FILENAMES = Set.of(
            "dockerfile", "makefile", ".env", ".gitignore", ".gitattributes"
    );

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
        FileToolType type = typeFor(name, directory, mediaType, extension);
        return new FileToolDescriptor(
                type,
                type.id(),
                type.label(),
                detailInlinePreviewable(type),
                type == FileToolType.TEXT
        );
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
        if (directory) {
            return FileToolType.DIRECTORY;
        }

        String normalizedName = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String normalizedExtension = normalizedExtension(name, extension);
        String normalizedMediaType = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);

        if (TEXT_EXTENSIONS.contains(normalizedExtension) || TEXT_FILENAMES.contains(normalizedName)) {
            return FileToolType.TEXT;
        }
        if (normalizedMediaType.startsWith("image/")) {
            return FileToolType.IMAGE;
        }
        if (normalizedMediaType.startsWith("video/")) {
            return FileToolType.VIDEO;
        }
        if ("application/pdf".equals(normalizedMediaType)) {
            return FileToolType.PDF;
        }
        if ("cbz".equals(normalizedExtension)) {
            return FileToolType.COMIC;
        }
        return FileToolType.HEX;
    }

    public boolean previewPageAvailable(FileDetail detail) {
        return previewPageAvailable(typeFor(detail));
    }

    public boolean previewPageAvailable(FileItem item) {
        return previewPageAvailable(typeFor(item));
    }

    public boolean previewPageAvailable(RecentListItem item) {
        return previewPageAvailable(typeFor(item));
    }

    public boolean previewPageAvailable(String name, boolean directory, String mediaType, String extension) {
        return previewPageAvailable(typeFor(name, directory, mediaType, extension));
    }

    public boolean detailInlinePreviewable(FileToolType type) {
        return type == FileToolType.IMAGE
                || type == FileToolType.VIDEO
                || type == FileToolType.PDF;
    }

    public boolean previewPageAvailable(FileToolType type) {
        return detailInlinePreviewable(type)
                || type == FileToolType.TEXT
                || type == FileToolType.COMIC;
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
}
