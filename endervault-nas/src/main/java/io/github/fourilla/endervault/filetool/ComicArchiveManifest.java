package io.github.fourilla.endervault.filetool;

import java.util.List;

public record ComicArchiveManifest(
        int pageCount,
        List<ComicPage> pages,
        ComicMetadata metadata
) {
    public boolean empty() {
        return pageCount == 0;
    }

    public ComicPage page(int index) {
        return pages.get(index);
    }
}
