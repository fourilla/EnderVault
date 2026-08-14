package io.github.fourilla.endervault.metadata;

public enum MetadataIssueAction {
    NONE("Review manually", false),
    REMOVE_METADATA("Remove metadata", true),
    DELETE_METADATA("Delete metadata", true),
    REVOKE_SHARE("Revoke shared link", true),
    DELETE_TRASH_RECORD_ITEM("Delete trash item", true),
    REMOVE_TRASH_RECORD("Remove trash record", true),
    DELETE_ORPHAN_TRASH_ITEM("Delete orphan trash item", true),
    DELETE_THUMBNAIL_CACHE("Delete cache file", true),
    DELETE_BOOKMARK_FAVICON_CACHE("Delete favicon cache", true),
    DELETE_FILE_STAGING("Delete staging file", true),
    DELETE_ARCHIVE_STAGING("Delete archive workspace", true),
    DELETE_TEXT_DRAFT("Delete text draft", true),
    DELETE_TEXT_DRAFT_METADATA("Delete draft metadata", true),
    DELETE_TEXT_DRAFT_CONTENT("Delete draft content", true),
    MOVE_BOOKMARK_TO_ROOT("Move bookmark to recovered", true),
    MOVE_BOOKMARK_TO_RECOVERED_DIRECTORY("Move bookmark to recovered", true);

    private final String label;
    private final boolean repairable;

    MetadataIssueAction(String label, boolean repairable) {
        this.label = label;
        this.repairable = repairable;
    }

    public String label() {
        return label;
    }

    public boolean repairable() {
        return repairable;
    }
}
