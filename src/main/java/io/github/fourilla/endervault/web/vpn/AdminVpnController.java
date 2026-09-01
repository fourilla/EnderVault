package io.github.fourilla.endervault.web.vpn;

import io.github.fourilla.endervault.web.support.AdminSpaViewService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminVpnController {

    private final AdminSpaViewService adminSpaViewService;

    public AdminVpnController(AdminSpaViewService adminSpaViewService) {
        this.adminSpaViewService = adminSpaViewService;
    }

    @GetMapping("/admin/vpn")
    public String vpnStatus(Model model) {
        return adminSpaViewService.render(model);
    }
}
