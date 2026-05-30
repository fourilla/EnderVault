package io.github.fourilla.endervault.filetool;

import org.springframework.core.io.Resource;

public record ComicPageResource(
        Resource resource,
        String mediaType,
        long contentLength,
        String filename
) {
}
