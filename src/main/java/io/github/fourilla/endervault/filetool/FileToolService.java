package io.github.fourilla.endervault.filetool;

import io.github.fourilla.endervault.storage.FileDetail;
import org.springframework.stereotype.Service;

@Service
public class FileToolService {

    private final FileActionRegistry fileActionRegistry;

    public FileToolService(FileActionRegistry fileActionRegistry) {
        this.fileActionRegistry = fileActionRegistry;
    }

    public FileToolDescriptor resolve(FileDetail detail) {
        return fileActionRegistry.resolve(detail);
    }
}
