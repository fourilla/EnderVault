package io.github.fourilla.endervault.web.vpn;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminVpnController {

    private final VpnRuntimeViewService vpnRuntimeViewService;

    public AdminVpnController(VpnRuntimeViewService vpnRuntimeViewService) {
        this.vpnRuntimeViewService = vpnRuntimeViewService;
    }

    @GetMapping("/admin/vpn")
    public String vpnStatus(Model model) {
        model.addAttribute("vpnRuntime", vpnRuntimeViewService.refresh());
        return "vpn-status";
    }
}
