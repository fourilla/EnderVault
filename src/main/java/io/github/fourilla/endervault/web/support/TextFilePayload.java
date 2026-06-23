package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.filetool.TextFileContent;

public record TextFilePayload(
        String content,
        boolean editable,
        String sizeLabel,
        String autoLoadSizeLabel,
        String manualLoadSizeLabel
) {
    public static TextFilePayload from(TextFileContent content) {
        return new TextFilePayload(
                content.content(),
                content.editable(),
                content.sizeLabel(),
                content.autoLoadSizeLabel(),
                content.manualLoadSizeLabel()
        );
    }
}
