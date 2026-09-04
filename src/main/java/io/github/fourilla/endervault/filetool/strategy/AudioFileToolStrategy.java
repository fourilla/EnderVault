package io.github.fourilla.endervault.filetool.strategy;

import io.github.fourilla.endervault.filetool.FileToolCapability;
import io.github.fourilla.endervault.filetool.FileToolContext;
import io.github.fourilla.endervault.filetool.FileToolDescriptor;
import io.github.fourilla.endervault.filetool.FileToolStrategy;
import io.github.fourilla.endervault.filetool.FileToolType;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AudioFileToolStrategy implements FileToolStrategy {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("mp3");

    @Override
    public int priority() {
        return 55;
    }

    @Override
    public boolean supports(FileToolContext context) {
        return !context.directory()
                && (context.mediaType().startsWith("audio/")
                || SUPPORTED_EXTENSIONS.contains(context.extension()));
    }

    @Override
    public FileToolDescriptor describe(FileToolContext context) {
        return FileToolDescriptor.of(
                FileToolType.AUDIO,
                FileToolCapability.INLINE_PREVIEW,
                FileToolCapability.PREVIEW_PAGE,
                FileToolCapability.SHARED_PREVIEW
        );
    }
}
