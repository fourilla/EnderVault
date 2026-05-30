package io.github.fourilla.endervault.filetool;

public enum FileToolType {
    DIRECTORY("directory", "Directory"),
    IMAGE("image", "Image Preview"),
    VIDEO("video", "Video Preview"),
    TEXT("text", "Text Editor"),
    PDF("pdf", "PDF Preview"),
    COMIC("comic", "Comic Viewer"),
    HEX("hex", "Hex Viewer");

    private final String id;
    private final String label;

    FileToolType(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }
}
