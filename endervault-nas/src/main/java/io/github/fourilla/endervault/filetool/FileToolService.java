package io.github.fourilla.endervault.filetool;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.storage.FileDetail;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class FileToolService {

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

    private final NasProperties.FileTools fileTools;

    public FileToolService(NasProperties nasProperties) {
        this.fileTools = nasProperties.getFileTools();
    }

    public FileToolDescriptor resolve(FileDetail detail) {
        FileToolType type = typeFor(detail);
        return new FileToolDescriptor(
                type,
                type.id(),
                type.label(),
                type == FileToolType.IMAGE || type == FileToolType.VIDEO || type == FileToolType.PDF,
                type == FileToolType.TEXT
        );
    }

    public TextFileContent readText(FileDetail detail, Path file) throws IOException {
        requireTextTool(detail);
        long fileSize = Files.size(file);
        long autoLoadMaxBytes = textAutoLoadMaxBytes();
        long manualLoadMaxBytes = textManualLoadMaxBytes();
        String autoLoadSizeLabel = ByteSizeFormatter.humanSize(autoLoadMaxBytes);
        String manualLoadSizeLabel = ByteSizeFormatter.humanSize(manualLoadMaxBytes);
        String sizeLabel = ByteSizeFormatter.humanSize(fileSize);
        if (fileSize > manualLoadMaxBytes) {
            return TextFileContent.unavailable(
                    "This text file is %s, which is larger than the manual text load limit of %s. Download it or use an external editor for now."
                            .formatted(sizeLabel, manualLoadSizeLabel),
                    autoLoadSizeLabel,
                    manualLoadSizeLabel,
                    sizeLabel
            );
        }
        if (fileSize > autoLoadMaxBytes) {
            return TextFileContent.manualRequired(
                    "This text file is %s. Load it manually only if you are sure the browser can handle it."
                            .formatted(sizeLabel),
                    autoLoadSizeLabel,
                    manualLoadSizeLabel,
                    sizeLabel
            );
        }

        return loadText(detail, file);
    }

    public TextFileContent loadText(FileDetail detail, Path file) throws IOException {
        requireTextTool(detail);
        long fileSize = Files.size(file);
        long autoLoadMaxBytes = textAutoLoadMaxBytes();
        long manualLoadMaxBytes = textManualLoadMaxBytes();
        String autoLoadSizeLabel = ByteSizeFormatter.humanSize(autoLoadMaxBytes);
        String manualLoadSizeLabel = ByteSizeFormatter.humanSize(manualLoadMaxBytes);
        String sizeLabel = ByteSizeFormatter.humanSize(fileSize);
        if (fileSize > manualLoadMaxBytes) {
            throw new StorageAccessException(
                    "Text file is larger than %s.".formatted(manualLoadSizeLabel)
            );
        }

        try {
            return TextFileContent.loaded(
                    Files.readString(file, StandardCharsets.UTF_8),
                    autoLoadSizeLabel,
                    manualLoadSizeLabel,
                    sizeLabel
            );
        } catch (MalformedInputException ex) {
            return TextFileContent.unavailable(
                    "This text file is not valid UTF-8, so the editor is disabled.",
                    autoLoadSizeLabel,
                    manualLoadSizeLabel,
                    sizeLabel
            );
        }
    }

    public void writeText(FileDetail detail, Path file, String content) throws IOException {
        requireTextTool(detail);
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        long maxBytes = textManualLoadMaxBytes();
        if (bytes.length > maxBytes) {
            throw new StorageAccessException("Text content is larger than %s.".formatted(ByteSizeFormatter.humanSize(maxBytes)));
        }

        Files.writeString(
                file,
                content == null ? "" : content,
                StandardCharsets.UTF_8,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private void requireTextTool(FileDetail detail) {
        if (typeFor(detail) != FileToolType.TEXT) {
            throw new StorageAccessException("This file is not editable as text.");
        }
    }

    private FileToolType typeFor(FileDetail detail) {
        if (detail.directory()) {
            return FileToolType.DIRECTORY;
        }
        String name = detail.name() == null ? "" : detail.name().toLowerCase(Locale.ROOT);
        String extension = detail.extension() == null ? "" : detail.extension().toLowerCase(Locale.ROOT);
        if (TEXT_EXTENSIONS.contains(extension) || TEXT_FILENAMES.contains(name)) {
            return FileToolType.TEXT;
        }
        if (detail.image()) {
            return FileToolType.IMAGE;
        }
        if (detail.video()) {
            return FileToolType.VIDEO;
        }
        if (detail.pdf()) {
            return FileToolType.PDF;
        }
        if ("cbz".equals(extension)) {
            return FileToolType.COMIC;
        }
        return FileToolType.HEX;
    }

    private long textAutoLoadMaxBytes() {
        return Math.max(1024L, fileTools.getTextAutoLoadMaxBytes());
    }

    private long textManualLoadMaxBytes() {
        return Math.max(textAutoLoadMaxBytes(), fileTools.getTextManualLoadMaxBytes());
    }
}
