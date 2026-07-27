package io.github.fourilla.endervault.filetool;

public interface FileToolStrategy {

    int priority();

    boolean supports(FileToolContext context);

    FileToolDescriptor describe(FileToolContext context);
}
