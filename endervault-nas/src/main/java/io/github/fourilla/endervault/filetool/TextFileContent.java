package io.github.fourilla.endervault.filetool;

public record TextFileContent(
        boolean loaded,
        boolean editable,
        boolean manualLoadAvailable,
        String content,
        String message,
        String autoLoadSizeLabel,
        String manualLoadSizeLabel,
        String sizeLabel
) {
    public static TextFileContent loaded(String content, String autoLoadSizeLabel, String manualLoadSizeLabel, String sizeLabel) {
        return new TextFileContent(true, true, false, content, "", autoLoadSizeLabel, manualLoadSizeLabel, sizeLabel);
    }

    public static TextFileContent manualRequired(String message, String autoLoadSizeLabel, String manualLoadSizeLabel, String sizeLabel) {
        return new TextFileContent(false, false, true, "", message, autoLoadSizeLabel, manualLoadSizeLabel, sizeLabel);
    }

    public static TextFileContent unavailable(String message, String autoLoadSizeLabel, String manualLoadSizeLabel, String sizeLabel) {
        return new TextFileContent(false, false, false, "", message, autoLoadSizeLabel, manualLoadSizeLabel, sizeLabel);
    }
}
