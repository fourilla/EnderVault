package io.github.fourilla.endervault.filetool.strategy;

import io.github.fourilla.endervault.filetool.FileToolCapability;
import io.github.fourilla.endervault.filetool.FileToolContext;
import io.github.fourilla.endervault.filetool.FileToolDescriptor;
import io.github.fourilla.endervault.filetool.FileToolStrategy;
import io.github.fourilla.endervault.filetool.FileToolType;
import org.springframework.stereotype.Component;

@Component
public class ImageFileToolStrategy implements FileToolStrategy {

    @Override
    public int priority() {
        return 40;
    }

    @Override
    public boolean supports(FileToolContext context) {
        return !context.directory() && context.mediaType().startsWith("image/");
    }

    @Override
    public FileToolDescriptor describe(FileToolContext context) {
        return FileToolDescriptor.of(
                FileToolType.IMAGE,
                FileToolCapability.INLINE_PREVIEW,
                FileToolCapability.PREVIEW_PAGE,
                FileToolCapability.SHARED_PREVIEW
        );
    }
}
