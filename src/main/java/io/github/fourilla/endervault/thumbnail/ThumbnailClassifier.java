package io.github.fourilla.endervault.thumbnail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class ThumbnailClassifier {

    boolean supports(Path file) throws IOException {
        return isVideoFile(file) || isComicFile(file) || isPdfFile(file);
    }

    boolean isVideoFile(Path file) throws IOException {
        String mediaType = Files.probeContentType(file);
        if (mediaType != null && mediaType.startsWith("video/")) {
            return true;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".mp4")
                || name.endsWith(".m4v")
                || name.endsWith(".mov")
                || name.endsWith(".mkv")
                || name.endsWith(".webm")
                || name.endsWith(".avi");
    }

    boolean isComicFile(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cbz");
    }

    boolean isPdfFile(Path file) throws IOException {
        String contentType = Files.probeContentType(file);
        if ("application/pdf".equalsIgnoreCase(contentType)) {
            return true;
        }
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }
}
