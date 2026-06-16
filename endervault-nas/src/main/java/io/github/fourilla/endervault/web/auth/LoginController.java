package io.github.fourilla.endervault.web.auth;

import io.github.fourilla.endervault.passkey.PasskeyService;
import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    private final PasskeyService passkeyService;

    public LoginController(PasskeyService passkeyService) {
        this.passkeyService = passkeyService;
    }

    @GetMapping("/login")
    public String login(Principal principal, Model model) {
        if (principal != null) {
            return "redirect:/files";
        }
        boolean passkeyAvailable = passkeyService.isEnabled() && passkeyService.hasCredentials();
        model.addAttribute("passkeysEnabled", passkeyService.isEnabled());
        model.addAttribute("passkeyAvailable", passkeyAvailable);
        model.addAttribute("passwordLoginEnabled", passkeyService.isPasswordLoginEnabled());
        return "login";
    }
}
