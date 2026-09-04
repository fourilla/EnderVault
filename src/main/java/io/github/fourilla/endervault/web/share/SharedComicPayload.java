package io.github.fourilla.endervault.web.share;

import io.github.fourilla.endervault.filetool.comic.ComicMetadata;

public record SharedComicPayload(
        String name,
        int pageCount,
        ComicMetadata metadata,
        String pageUrl
) {
}
