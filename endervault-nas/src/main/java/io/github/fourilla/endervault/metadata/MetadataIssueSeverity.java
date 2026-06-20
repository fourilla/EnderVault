package io.github.fourilla.endervault.metadata;

public enum MetadataIssueSeverity {
    INFO("Info", "info"),
    WARNING("Warning", "warning"),
    DANGER("Danger", "error");

    private final String label;
    private final String cssClass;

    MetadataIssueSeverity(String label, String cssClass) {
        this.label = label;
        this.cssClass = cssClass;
    }

    public String label() {
        return label;
    }

    public String cssClass() {
        return cssClass;
    }
}
