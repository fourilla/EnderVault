package io.github.fourilla.endervault.web.api.v1.settings;

import io.github.fourilla.endervault.settings.BookmarkSettingsService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import java.io.IOException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings/bookmarks")
public class BookmarkSettingsApiController {

    private final BookmarkSettingsService bookmarkSettingsService;

    public BookmarkSettingsApiController(BookmarkSettingsService bookmarkSettingsService) {
        this.bookmarkSettingsService = bookmarkSettingsService;
    }

    @PostMapping
    public ActionResponse save(@RequestParam MultiValueMap<String, String> parameters) throws IOException {
        bookmarkSettingsService.save(bookmarkSettingsService.updateFrom(parameters));
        return ActionResponse.ok(FlashNotification.success("Bookmark settings saved and applied."));
    }
}
