package io.github.fourilla.endervault.web.settings;

import io.github.fourilla.endervault.settings.BookmarkSettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminBookmarkSettingsController {

    private final BookmarkSettingsService bookmarkSettingsService;

    public AdminBookmarkSettingsController(BookmarkSettingsService bookmarkSettingsService) {
        this.bookmarkSettingsService = bookmarkSettingsService;
    }

    @GetMapping("/admin/settings/bookmarks")
    public String bookmarkSettings(Model model) {
        model.addAttribute("settings", bookmarkSettingsService.currentSettings());
        return "bookmark-settings";
    }
}
