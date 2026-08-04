package io.github.fourilla.endervault.stickynote;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class StickyNotePageCatalog {

    private final Map<String, PageDefinition> pages = pages();

    public PageDefinition byPath(String path) {
        return pages.values().stream()
                .filter(page -> page.path().equals(path))
                .findFirst()
                .orElse(null);
    }

    public PageDefinition byKey(String key) {
        return pages.get(key == null ? "" : key.trim());
    }

    private Map<String, PageDefinition> pages() {
        Map<String, PageDefinition> values = new LinkedHashMap<>();
        register(values, "dashboard", "Dashboard", "/admin/dashboard");
        register(values, "activity-logs", "Activity Logs", "/admin/logs");
        register(values, "shared-links", "Shared Links", "/admin/shares");
        register(values, "trash", "Trash", "/admin/trash");
        register(values, "metadata-inspector", "Metadata Inspector", "/admin/metadata");
        register(values, "active-sessions", "Active Sessions", "/admin/sessions");
        register(values, "settings", "Settings", "/admin/settings");
        register(values, "general-settings", "General Settings", "/admin/settings/general");
        register(values, "account-settings", "Account Settings", "/admin/settings/account");
        register(values, "bookmark-settings", "Bookmark Settings", "/admin/settings/bookmarks");
        register(values, "session-settings", "Session Settings", "/admin/settings/sessions");
        register(values, "vpn-settings", "VPN Settings", "/admin/settings/vpn");
        register(values, "telegram-alerts", "Telegram Alerts", "/admin/settings/telegram-alerts");
        register(values, "passkeys", "Passkeys", "/admin/settings/passkeys");
        register(values, "vpn-status", "VPN Status", "/admin/vpn");
        register(values, "remote-download", "Remote Download", "/admin/utils/remote-download");
        register(values, "recent", "Recent", "/files/recent");
        register(values, "favorites", "Favorites", "/files/favorites");
        register(values, "bookmarks", "Bookmarks", "/files/bookmarks");
        register(values, "sticky-notes", "Sticky Notes", "/admin/sticky-notes");
        return Map.copyOf(values);
    }

    private void register(Map<String, PageDefinition> values, String key, String label, String path) {
        values.put(key, new PageDefinition(key, label, path));
    }

    public record PageDefinition(String key, String label, String path) {
    }
}
