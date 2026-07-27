package io.github.fourilla.endervault.filetool.strategy;

import io.github.fourilla.endervault.filetool.FileToolContext;
import io.github.fourilla.endervault.filetool.FileToolDescriptor;
import io.github.fourilla.endervault.filetool.FileToolStrategy;
import io.github.fourilla.endervault.filetool.FileToolType;
import org.springframework.stereotype.Component;

@Component
public class HexFileToolStrategy implements FileToolStrategy {

    @Override
    public int priority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean supports(FileToolContext context) {
        return true;
    }

    @Override
    public FileToolDescriptor describe(FileToolContext context) {
        return FileToolDescriptor.of(FileToolType.HEX);
    }
}
