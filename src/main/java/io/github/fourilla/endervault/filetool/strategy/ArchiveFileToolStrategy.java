package io.github.fourilla.endervault.filetool.strategy;

import io.github.fourilla.endervault.filetool.FileToolCapability;
import io.github.fourilla.endervault.filetool.FileToolContext;
import io.github.fourilla.endervault.filetool.FileToolDescriptor;
import io.github.fourilla.endervault.filetool.FileToolStrategy;
import io.github.fourilla.endervault.filetool.FileToolType;
import io.github.fourilla.endervault.filetool.archive.ArchiveFormat;
import org.springframework.stereotype.Component;

@Component
public class ArchiveFileToolStrategy implements FileToolStrategy {

    @Override
    public int priority() {
        return 35;
    }

    @Override
    public boolean supports(FileToolContext context) {
        return !context.directory() && ArchiveFormat.fromFilename(context.normalizedName()).isPresent();
    }

    @Override
    public FileToolDescriptor describe(FileToolContext context) {
        return FileToolDescriptor.of(
                FileToolType.ARCHIVE,
                FileToolCapability.ARCHIVE_BROWSE,
                FileToolCapability.ARCHIVE_EXTRACT
        );
    }
}
