package io.github.fourilla.endervault.stickynote;

public enum StickyNoteSurface {
    PAGE("Page"),
    BROWSER("Browser"),
    DETAIL("Detail"),
    SEARCH("Search");

    private final String label;

    StickyNoteSurface(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
