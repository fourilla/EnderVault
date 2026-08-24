package io.github.fourilla.endervault.web.auth;

import io.github.fourilla.endervault.passkey.PasskeyService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminPasskeyController {

    private final PasskeyService passkeyService;

    public AdminPasskeyController(PasskeyService passkeyService) {
        this.passkeyService = passkeyService;
    }

    @GetMapping("/admin/settings/passkeys")
    public String page(Model model) {
        model.addAttribute("passkeys", passkeyService.listCredentials());
        model.addAttribute("passkeysEnabled", passkeyService.isEnabled());
        model.addAttribute("passwordLoginEnabled", passkeyService.isPasswordLoginEnabled());
        model.addAttribute("rpId", passkeyService.rpId());
        model.addAttribute("allowedOrigins", passkeyService.allowedOrigins());
        return "passkeys";
    }

}
