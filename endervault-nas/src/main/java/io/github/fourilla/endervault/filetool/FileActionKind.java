package io.github.fourilla.endervault.filetool;

public enum FileActionKind {
    DETAILS("details", "Details", "fas fa-circle-info"),
    DOWNLOAD("download", "Download", "fas fa-download"),
    PREVIEW("preview", "Preview", "fas fa-eye");

    private final String id;
    private final String label;
    private final String icon;

    FileActionKind(String id, String label, String icon) {
        this.id = id;
        this.label = label;
        this.icon = icon;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String icon() {
        return icon;
    }
}
