package io.github.fourilla.endervault.filetool.text;

public class TextDraftLeaseException extends RuntimeException {

    private final TextDraftStatus status;

    public TextDraftLeaseException(String message, TextDraftStatus status) {
        super(message);
        this.status = status;
    }

    public TextDraftStatus status() {
        return status;
    }
}
