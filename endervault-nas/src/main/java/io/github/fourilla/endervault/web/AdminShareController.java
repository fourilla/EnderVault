package io.github.fourilla.endervault.web;

import io.github.fourilla.endervault.share.ShareLinkService;
import java.io.IOException;
import java.time.Instant;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Controller
public class AdminShareController {

    private final ShareLinkService shareLinkService;

    public AdminShareController(ShareLinkService shareLinkService) {
        this.shareLinkService = shareLinkService;
    }

    @GetMapping("/files/shares")
    public String shares(Model model) throws IOException {
        model.addAttribute("shares", shareLinkService.list());
        model.addAttribute("shareBaseUrl", ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/s/")
                .toUriString());
        return "shares";
    }

    @PostMapping("/files/shares/revoke")
    public String revoke(@RequestParam("token") String token, RedirectAttributes redirectAttributes)
            throws IOException {
        shareLinkService.revoke(token);
        redirectAttributes.addFlashAttribute("message", "Share link revoked.");
        return "redirect:/files/shares";
    }

    @PostMapping("/files/shares/delete")
    public String delete(@RequestParam("token") String token, RedirectAttributes redirectAttributes)
            throws IOException {
        shareLinkService.delete(token);
        redirectAttributes.addFlashAttribute("message", "Share link deleted.");
        return "redirect:/files/shares";
    }

    @PostMapping("/files/shares/delete-expired")
    public String deleteExpired(RedirectAttributes redirectAttributes) throws IOException {
        int deletedCount = shareLinkService.deleteExpired(Instant.now());
        redirectAttributes.addFlashAttribute("message", "Deleted %d expired share links.".formatted(deletedCount));
        return "redirect:/files/shares";
    }
}
