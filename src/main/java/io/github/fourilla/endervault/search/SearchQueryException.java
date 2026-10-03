package io.github.fourilla.endervault.search;

public final class SearchQueryException extends IllegalArgumentException {

    private final int position;

    SearchQueryException(String message, int position) {
        super(message);
        this.position = position;
    }

    /** Zero-based UTF-16 offset in the submitted query. */
    public int position() {
        return position;
    }
}
