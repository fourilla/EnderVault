package io.github.fourilla.endervault.web.api.v1.fs;

import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.web.support.FilePreviewSupport;
import java.time.Instant;
import org.springframework.web.util.UriComponentsBuilder;

public record FileBrowserEntryPayload(
        String name,
        String path,
        String parentPath,
        String type,
        String typeLabel,
        String extensionLabel,
        long size,
        String sizeLabel,
        Instant modifiedAt,
        String modifiedLabel,
        String mediaType,
        boolean previewable,
        boolean streamable,
        boolean hidden,
        boolean favorite,
        boolean image,
        boolean video,
        boolean pdf,
        boolean comic,
        String detailUrl,
        String downloadUrl,
        String previewUrl,
        String thumbnailUrl
) {

    static FileBrowserEntryPayload from(
            FileItem item,
            boolean favorite,
            FilePreviewSupport filePreviewSupport
    ) {
        boolean directory = item.directory();
        return new FileBrowserEntryPayload(
                item.name(),
                item.path(),
                item.parentPath(),
                directory ? "directory" : "file",
                item.typeLabel(),
                item.extensionLabel(),
                item.size(),
                item.sizeLabel(),
                item.modifiedAt(),
                item.modifiedLabel(),
                item.mediaType(),
                item.previewable(),
                item.streamable(),
                item.hidden(),
                favorite,
                item.image(),
                item.video(),
                item.pdf(),
                item.comic(),
                detailUrl(item.path()),
                directory ? null : downloadUrl(item.path()),
                item.previewable() ? filePreviewSupport.previewUrl(item) : null,
                thumbnailUrl(item, filePreviewSupport)
        );
    }

    private static String detailUrl(String path) {
        return UriComponentsBuilder.fromPath("/files/detail")
                .queryParam("path", path)
                .build()
                .encode()
                .toUriString();
    }

    private static String downloadUrl(String path) {
        return UriComponentsBuilder.fromPath("/files/detail/download")
                .queryParam("path", path)
                .build()
                .encode()
                .toUriString();
    }

    private static String thumbnailUrl(FileItem item, FilePreviewSupport filePreviewSupport) {
        if (item.directory() || !(item.image() || item.video() || item.pdf() || item.comic())) {
            return null;
        }
        return filePreviewSupport.cardMediaUrl(item);
    }
}
