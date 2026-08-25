package io.github.fourilla.endervault.web.share;

import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.web.support.ShareLinkView;
import io.github.fourilla.endervault.web.support.ShareUrlBuilder;
import java.io.IOException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminShareController {

    private final ShareLinkService shareLinkService;
    private final ShareUrlBuilder shareUrlBuilder;

    public AdminShareController(
            ShareLinkService shareLinkService,
            ShareUrlBuilder shareUrlBuilder
    ) {
        this.shareLinkService = shareLinkService;
        this.shareUrlBuilder = shareUrlBuilder;
    }

    @GetMapping("/admin/shares")
    public String shares(Model model) throws IOException {
        String shareBaseUrl = shareBaseUrl();
        model.addAttribute("shares", shareLinkService.list()
                .stream()
                .map(shareLink -> ShareLinkView.from(shareLink, shareBaseUrl, shareUrlBuilder.directDownloadLinkEnabled()))
                .toList());
        return "shares";
    }

    private String shareBaseUrl() {
        return shareUrlBuilder.shareBaseUrl();
    }
}
