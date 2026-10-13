package io.github.fourilla.endervault.web.api.v1.app;

import io.github.fourilla.endervault.web.support.AdminShellStateService;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/app")
public class AdminAppBootstrapApiController {

    private final AdminShellStateService shellStateService;

    public AdminAppBootstrapApiController(AdminShellStateService shellStateService) {
        this.shellStateService = shellStateService;
    }

    @GetMapping(value = "/bootstrap", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdminAppBootstrapPayload> bootstrap(HttpServletRequest request, Principal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AdminAppBootstrapPayload.from(
                principal.getName(),
                shellStateService,
                shellStateService.favorites(request),
                shellStateService.browserUiConfig(request)
        ));
    }
}
