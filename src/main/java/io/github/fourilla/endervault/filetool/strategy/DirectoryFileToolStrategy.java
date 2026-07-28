package io.github.fourilla.endervault.filetool.strategy;

import io.github.fourilla.endervault.filetool.FileToolContext;
import io.github.fourilla.endervault.filetool.FileToolDescriptor;
import io.github.fourilla.endervault.filetool.FileToolStrategy;
import io.github.fourilla.endervault.filetool.FileToolType;
import org.springframework.stereotype.Component;

@Component
public class DirectoryFileToolStrategy implements FileToolStrategy {

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean supports(FileToolContext context) {
        return context.directory();
    }

    @Override
    public FileToolDescriptor describe(FileToolContext context) {
        return FileToolDescriptor.of(FileToolType.DIRECTORY);
    }
}
