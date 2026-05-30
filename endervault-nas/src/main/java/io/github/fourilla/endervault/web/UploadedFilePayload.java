package io.github.fourilla.endervault.web;

import io.github.fourilla.endervault.storage.FileItem;

public record UploadedFilePayload(
        String name,
        String path,
        String parentPath,
        String mediaType,
        String typeLabel,
        String sizeLabel,
        String modifiedLabel,
        boolean image,
        boolean video,
        boolean previewable
) {

    static UploadedFilePayload from(FileItem item) {
        return new UploadedFilePayload(
                item.name(),
                item.path(),
                item.parentPath(),
                item.mediaType(),
                item.typeLabel(),
                item.sizeLabel(),
                item.modifiedLabel(),
                item.image(),
                item.video(),
                item.previewable()
        );
    }
}
