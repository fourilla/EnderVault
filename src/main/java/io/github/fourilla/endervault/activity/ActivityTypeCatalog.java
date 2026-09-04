package io.github.fourilla.endervault.activity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ActivityTypeCatalog {

    private static final List<ActivityTypeOption> OPTIONS = List.of(
            new ActivityTypeOption("login-success", "LOGIN_SUCCESS", "Login success", "Authentication", true),
            new ActivityTypeOption("login-failure", "LOGIN_FAILURE", "Login failure", "Authentication", true),
            new ActivityTypeOption("account-update", "ACCOUNT_UPDATE", "Account update", "Authentication", false),
            new ActivityTypeOption("session-policy-update", "SESSION_POLICY_UPDATE", "Session policy update", "Authentication", false),
            new ActivityTypeOption("session-revoke", "SESSION_REVOKE", "Session revoke", "Authentication", false),
            new ActivityTypeOption("passkey-register", "PASSKEY_REGISTER", "Passkey register", "Authentication", false),
            new ActivityTypeOption("passkey-delete", "PASSKEY_DELETE", "Passkey delete", "Authentication", false),
            new ActivityTypeOption("upload", "UPLOAD", "Upload", "File management", false),
            new ActivityTypeOption("pending-file-decision-resolve", "PENDING_FILE_DECISION_RESOLVE", "Pending file decision resolve", "File management", false),
            new ActivityTypeOption("create-directory", "CREATE_DIRECTORY", "Create directory", "File management", false),
            new ActivityTypeOption("create-file", "CREATE_FILE", "Create file", "File management", false),
            new ActivityTypeOption("text-save", "TEXT_SAVE", "Text save", "File management", false),
            new ActivityTypeOption("text-save-as", "TEXT_SAVE_AS", "Text draft Save As", "File management", false),
            new ActivityTypeOption("rename", "RENAME", "Rename", "File management", false),
            new ActivityTypeOption("hidden-change", "HIDDEN_CHANGE", "Hidden state change", "File management", false),
            new ActivityTypeOption("move", "MOVE", "Move", "File management", false),
            new ActivityTypeOption("copy", "COPY", "Copy", "File management", false),
            new ActivityTypeOption("download", "DOWNLOAD", "Download", "File management", false),
            new ActivityTypeOption("download-zip", "DOWNLOAD_ZIP", "Download ZIP", "File management", false),
            new ActivityTypeOption("archive-create-queued", "ARCHIVE_CREATE_QUEUED", "ZIP creation queued", "File management", false),
            new ActivityTypeOption("archive-create-complete", "ARCHIVE_CREATE_COMPLETE", "ZIP creation complete", "File management", false),
            new ActivityTypeOption("archive-create-failed", "ARCHIVE_CREATE_FAILED", "ZIP creation failed", "File management", false),
            new ActivityTypeOption("archive-create-canceled", "ARCHIVE_CREATE_CANCELED", "ZIP creation canceled", "File management", false),
            new ActivityTypeOption("archive-extract-queued", "ARCHIVE_EXTRACT_QUEUED", "Archive extraction queued", "File management", false),
            new ActivityTypeOption("archive-extract-complete", "ARCHIVE_EXTRACT_COMPLETE", "Archive extraction complete", "File management", false),
            new ActivityTypeOption("archive-extract-failed", "ARCHIVE_EXTRACT_FAILED", "Archive extraction failed", "File management", false),
            new ActivityTypeOption("archive-extract-canceled", "ARCHIVE_EXTRACT_CANCELED", "Archive extraction canceled", "File management", false),
            new ActivityTypeOption("trash-move", "TRASH_MOVE", "Trash move", "Trash", false),
            new ActivityTypeOption("trash-restore", "TRASH_RESTORE", "Trash restore", "Trash", false),
            new ActivityTypeOption("trash-delete", "TRASH_DELETE", "Trash delete", "Trash", false),
            new ActivityTypeOption("trash-empty", "TRASH_EMPTY", "Trash empty", "Trash", false),
            new ActivityTypeOption("share-create", "SHARE_CREATE", "Share create", "Share links", false),
            new ActivityTypeOption("share-revoke", "SHARE_REVOKE", "Share revoke", "Share links", false),
            new ActivityTypeOption("share-delete", "SHARE_DELETE", "Share delete", "Share links", false),
            new ActivityTypeOption("share-delete-expired", "SHARE_DELETE_EXPIRED", "Delete expired shares", "Share links", false),
            new ActivityTypeOption("share-access", "SHARE_ACCESS", "Share access", "Share links", false),
            new ActivityTypeOption("share-preview", "SHARE_PREVIEW", "Share preview", "Share links", false),
            new ActivityTypeOption("share-download", "SHARE_DOWNLOAD", "Share download", "Share links", false),
            new ActivityTypeOption("share-download-zip", "SHARE_DOWNLOAD_ZIP", "Share ZIP download", "Share links", false),
            new ActivityTypeOption("file-request-create", "FILE_REQUEST_CREATE", "File request create", "File requests", false),
            new ActivityTypeOption("file-request-revoke", "FILE_REQUEST_REVOKE", "File request revoke", "File requests", false),
            new ActivityTypeOption("file-request-delete", "FILE_REQUEST_DELETE", "File request delete", "File requests", false),
            new ActivityTypeOption("file-request-access", "FILE_REQUEST_ACCESS", "File request access", "File requests", false),
            new ActivityTypeOption("file-request-upload", "FILE_REQUEST_UPLOAD", "File request upload", "File requests", false),
            new ActivityTypeOption("remote-download-queued", "REMOTE_DOWNLOAD_QUEUED", "Remote download queued", "Remote download", false),
            new ActivityTypeOption("remote-download-complete", "REMOTE_DOWNLOAD_COMPLETE", "Remote download complete", "Remote download", false),
            new ActivityTypeOption("remote-download-failed", "REMOTE_DOWNLOAD_FAILED", "Remote download failed", "Remote download", false),
            new ActivityTypeOption("remote-download-canceled", "REMOTE_DOWNLOAD_CANCELED", "Remote download canceled", "Remote download", false),
            new ActivityTypeOption("outbound-route-change", "OUTBOUND_ROUTE_CHANGE", "Outbound route change", "Network", false),
            new ActivityTypeOption("vpn-control", "VPN_CONTROL", "VPN control", "Network", false),
            new ActivityTypeOption("bookmark-directory-create", "BOOKMARK_DIRECTORY_CREATE", "Bookmark directory create", "Bookmarks and recent", false),
            new ActivityTypeOption("bookmark-link-create", "BOOKMARK_LINK_CREATE", "Bookmark link create", "Bookmarks and recent", false),
            new ActivityTypeOption("bookmark-bulk-create", "BOOKMARK_BULK_CREATE", "Bookmark bulk create", "Bookmarks and recent", false),
            new ActivityTypeOption("bookmark-update", "BOOKMARK_UPDATE", "Bookmark update", "Bookmarks and recent", false),
            new ActivityTypeOption("bookmark-metadata-fetch", "BOOKMARK_METADATA_FETCH", "Bookmark metadata fetch", "Bookmarks and recent", false),
            new ActivityTypeOption("bookmark-delete", "BOOKMARK_DELETE", "Bookmark delete", "Bookmarks and recent", false),
            new ActivityTypeOption("bookmark-delete-selected", "BOOKMARK_DELETE_SELECTED", "Bookmark delete selected", "Bookmarks and recent", false),
            new ActivityTypeOption("bookmark-open", "BOOKMARK_OPEN", "Bookmark open", "Bookmarks and recent", false),
            new ActivityTypeOption("recent-clear", "RECENT_CLEAR", "Recent clear", "Bookmarks and recent", false),
            new ActivityTypeOption("sticky-note-create", "STICKY_NOTE_CREATE", "Sticky note create", "Notes", false),
            new ActivityTypeOption("sticky-note-delete", "STICKY_NOTE_DELETE", "Sticky note delete", "Notes", false)
    );

    private ActivityTypeCatalog() {
    }

    public static List<ActivityTypeOption> options() {
        return OPTIONS;
    }

    public static Map<String, Boolean> defaultTelegramActivity() {
        Map<String, Boolean> defaults = new LinkedHashMap<>();
        for (ActivityTypeOption option : OPTIONS) {
            defaults.put(option.key(), option.defaultTelegramEnabled());
        }
        return defaults;
    }

    public static String normalizeKey(String value) {
        return value == null
                ? ""
                : value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    public record ActivityTypeOption(
            String key,
            String type,
            String label,
            String group,
            boolean defaultTelegramEnabled
    ) {
    }
}
