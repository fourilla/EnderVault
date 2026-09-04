package io.github.fourilla.endervault.filetool.strategy;

import io.github.fourilla.endervault.filetool.FileToolCapability;
import io.github.fourilla.endervault.filetool.FileToolContext;
import io.github.fourilla.endervault.filetool.FileToolDescriptor;
import io.github.fourilla.endervault.filetool.FileToolStrategy;
import io.github.fourilla.endervault.filetool.FileToolType;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class TextFileToolStrategy implements FileToolStrategy {

    private static final Set<String> MARKDOWN_EXTENSIONS = Set.of("md", "markdown");
    private static final Set<String> EXTENSIONS = Set.of(
            "txt", "text", "md", "markdown", "log",
            "csv", "tsv", "json", "jsonl", "xml", "html", "htm", "css",
            "js", "mjs", "cjs", "ts", "tsx", "jsx",
            "java", "c", "h", "cpp", "hpp", "cc", "cs",
            "py", "rb", "go", "rs", "php",
            "sh", "bash", "zsh", "bat", "cmd", "ps1",
            "sql", "properties", "conf", "cfg", "ini", "yml", "yaml", "toml"
    );
    private static final Set<String> FILENAMES = Set.of(
            "dockerfile", "makefile", ".env", ".gitignore", ".gitattributes"
    );

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public boolean supports(FileToolContext context) {
        return !context.directory()
                && (EXTENSIONS.contains(context.extension()) || FILENAMES.contains(context.normalizedName()));
    }

    @Override
    public FileToolDescriptor describe(FileToolContext context) {
        if (MARKDOWN_EXTENSIONS.contains(context.extension())) {
            return FileToolDescriptor.of(
                    FileToolType.TEXT,
                    FileToolCapability.PREVIEW_PAGE,
                    FileToolCapability.SHARED_PREVIEW,
                    FileToolCapability.TEXT_SOURCE,
                    FileToolCapability.TEXT_EDIT,
                    FileToolCapability.MARKDOWN_RENDER
            );
        }
        return FileToolDescriptor.of(
                FileToolType.TEXT,
                FileToolCapability.PREVIEW_PAGE,
                FileToolCapability.SHARED_PREVIEW,
                FileToolCapability.TEXT_SOURCE,
                FileToolCapability.TEXT_EDIT
        );
    }
}
