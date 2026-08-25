package io.github.fourilla.endervault.web.api.v1.file;

import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.BrowserPreferenceCookies;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/browser-preferences")
public class BrowserPreferenceApiController {

    @PostMapping("/reset")
    public ActionResponse reset(
            @RequestParam(value = "target", required = false) String target,
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "q", required = false) String query,
            HttpServletResponse response
    ) {
        if ("read-only".equalsIgnoreCase(target)) {
            BrowserPreferenceCookies.clear(response, BrowserPreferenceCookies.READ_ONLY);
            return ActionResponse.redirect(
                    FlashNotification.success("Read-only preferences reset."),
                    readOnlyUrl(path)
            );
        }

        if ("recent".equalsIgnoreCase(target)) {
            BrowserPreferenceCookies.clear(response, BrowserPreferenceCookies.RECENT);
            return ActionResponse.redirect(
                    FlashNotification.success("Recent preferences reset."),
                    recentUrl(query)
            );
        }

        BrowserPreferenceCookies.clear(response, BrowserPreferenceCookies.FILES);
        return ActionResponse.redirect(
                FlashNotification.success("File browser preferences reset."),
                filesUrl(path)
        );
    }

    private String filesUrl(String path) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/files");
        if (path != null && !path.isBlank()) {
            builder.queryParam("path", path);
        }
        return builder.build().encode().toUriString();
    }

    private String recentUrl(String query) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/files/recent");
        if (query != null && !query.isBlank()) {
            builder.queryParam("q", query);
        }
        return builder.build().encode().toUriString();
    }

    private String readOnlyUrl(String path) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/files/read-only");
        if (path != null && !path.isBlank()) {
            builder.queryParam("path", path);
        }
        return builder.build().encode().toUriString();
    }
}
