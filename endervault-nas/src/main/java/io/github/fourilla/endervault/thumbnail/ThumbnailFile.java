package io.github.fourilla.endervault.thumbnail;

import java.nio.file.Path;

public record ThumbnailFile(Path path, String mediaType, boolean generated) {
}
