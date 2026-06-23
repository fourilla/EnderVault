package io.github.fourilla.endervault.web.auth;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home(Principal principal) {
        return principal == null ? "redirect:/login" : "redirect:/files";
    }
}
