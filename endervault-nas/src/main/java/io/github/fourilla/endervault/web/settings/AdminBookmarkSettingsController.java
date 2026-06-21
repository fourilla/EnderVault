package io.github.fourilla.endervault.web.settings;

import io.github.fourilla.endervault.settings.BookmarkSettingsService;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

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

    @PostMapping("/admin/settings/bookmarks")
    public Object saveBookmarkSettings(
            @RequestParam MultiValueMap<String, String> parameters,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        try {
            bookmarkSettingsService.save(bookmarkSettingsService.updateFrom(parameters));
            return ActionResponseSupport.ok(
                    request,
                    redirectAttributes,
                    FlashNotification.success("Bookmark settings saved."),
                    redirectToBookmarkSettings()
            );
        } catch (IllegalArgumentException ex) {
            return ActionResponseSupport.badRequest(
                    request,
                    redirectAttributes,
                    FlashNotification.error(ex.getMessage()),
                    redirectToBookmarkSettings()
            );
        } catch (IOException ex) {
            return ActionResponseSupport.error(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    request,
                    redirectAttributes,
                    FlashNotification.error("Bookmark settings could not be saved."),
                    redirectToBookmarkSettings()
            );
        }
    }

    private String redirectToBookmarkSettings() {
        return "redirect:/admin/settings/bookmarks";
    }
}
