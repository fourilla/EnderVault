package io.github.fourilla.endervault.filetool;

public record ComicPage(
        int index,
        String entryName,
        String displayName,
        String mediaType,
        long size
) {
    public int number() {
        return index + 1;
    }
}
