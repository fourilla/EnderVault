package io.github.fourilla.endervault.filetool.text;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.filetool.FileToolType;
import io.github.fourilla.endervault.filetool.TextFileContent;
import io.github.fourilla.endervault.storage.FileDetail;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.stereotype.Service;

@Service
public class TextFileService {

    private final NasProperties.FileTools fileTools;
    private final FileActionRegistry fileActionRegistry;

    public TextFileService(NasProperties nasProperties, FileActionRegistry fileActionRegistry) {
        this.fileTools = nasProperties.getFileTools();
        this.fileActionRegistry = fileActionRegistry;
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
        byte[] bytes = validatedBytes(content);
        Files.write(
                file,
                bytes,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    public byte[] validatedBytes(String content) {
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        long maxBytes = textManualLoadMaxBytes();
        if (bytes.length > maxBytes) {
            throw new StorageAccessException(
                    "Text content is larger than %s.".formatted(ByteSizeFormatter.humanSize(maxBytes))
            );
        }
        return bytes;
    }

    public void requireTextTool(FileDetail detail) {
        if (fileActionRegistry.typeFor(detail) != FileToolType.TEXT) {
            throw new StorageAccessException("This file is not editable as text.");
        }
    }

    private long textAutoLoadMaxBytes() {
        return Math.max(1024L, fileTools.getTextAutoLoadMaxBytes());
    }

    private long textManualLoadMaxBytes() {
        return Math.max(textAutoLoadMaxBytes(), fileTools.getTextManualLoadMaxBytes());
    }
}
