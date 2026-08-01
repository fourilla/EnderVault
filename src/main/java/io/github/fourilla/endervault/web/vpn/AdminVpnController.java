package io.github.fourilla.endervault.web.vpn;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealth;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthService;
import io.github.fourilla.endervault.outbound.vpn.control.VpnControlException;
import io.github.fourilla.endervault.outbound.vpn.control.VpnControlService;
import io.github.fourilla.endervault.outbound.vpn.control.VpnControlStatus;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.VpnRuntimeStatusView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminVpnController {

    private static final String VPN_PATH = "/admin/vpn";

    private final VpnControlService vpnControlService;
    private final VpnProxyHealthService vpnProxyHealthService;
    private final VpnRuntimeViewService vpnRuntimeViewService;
    private final ActivityLogService activityLogService;

    public AdminVpnController(
            VpnControlService vpnControlService,
            VpnProxyHealthService vpnProxyHealthService,
            VpnRuntimeViewService vpnRuntimeViewService,
            ActivityLogService activityLogService
    ) {
        this.vpnControlService = vpnControlService;
        this.vpnProxyHealthService = vpnProxyHealthService;
        this.vpnRuntimeViewService = vpnRuntimeViewService;
        this.activityLogService = activityLogService;
    }

    @GetMapping(VPN_PATH)
    public String vpnStatus(Model model) {
        model.addAttribute("vpnRuntime", refreshView());
        return "vpn-status";
    }

    @GetMapping(value = VPN_PATH + "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public VpnRuntimeStatusView status() {
        return refreshView();
    }

    @PostMapping(VPN_PATH + "/refresh")
    public Object refresh(
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        VpnRuntimeStatusView view = refreshView();
        FlashNotification notification = FlashNotification.success("VPN status refreshed.");
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                redirectToVpn(),
                VpnControlActionResponse.ok(notification, view)
        );
    }

    @PostMapping(VPN_PATH + "/connect")
    public Object connect(
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        return control("connect", false, request, redirectAttributes);
    }

    @PostMapping(VPN_PATH + "/disconnect")
    public Object disconnect(
            @RequestParam(defaultValue = "false") boolean force,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        return control("disconnect", force, request, redirectAttributes);
    }

    @PostMapping(VPN_PATH + "/reconnect")
    public Object reconnect(
            @RequestParam(defaultValue = "false") boolean force,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        return control("reconnect", force, request, redirectAttributes);
    }

    private Object control(
            String action,
            boolean force,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        int activeVpnTasks = vpnRuntimeViewService.activeVpnTasks();
        if (!action.equals("connect") && activeVpnTasks > 0 && !force) {
            return ActionResponseSupport.error(
                    HttpStatus.CONFLICT,
                    request,
                    redirectAttributes,
                    FlashNotification.warning(
                            activeVpnTasks + " VPN remote download task(s) are active. Confirm the action to continue."
                    ),
                    redirectToVpn()
            );
        }

        try {
            VpnControlStatus status = switch (action) {
                case "connect" -> vpnControlService.connect();
                case "disconnect" -> vpnControlService.disconnect();
                case "reconnect" -> vpnControlService.reconnect();
                default -> throw new IllegalArgumentException("Unsupported VPN control action.");
            };
            VpnProxyHealth health = vpnProxyHealthService.refresh();
            VpnRuntimeStatusView view = view(status, health);
            FlashNotification notification = notification(action, status.running());
            recordControl(action, true, notification.message(), request, view);
            return ActionResponseSupport.ok(
                    request,
                    redirectAttributes,
                    notification,
                    redirectToVpn(),
                    VpnControlActionResponse.ok(notification, view)
            );
        } catch (VpnControlException | IllegalArgumentException ex) {
            recordControl(action, false, ex.getMessage(), request, null);
            return ActionResponseSupport.badRequest(
                    request,
                    redirectAttributes,
                    FlashNotification.error(ex.getMessage()),
                    redirectToVpn()
            );
        }
    }

    private VpnRuntimeStatusView refreshView() {
        return vpnRuntimeViewService.refresh();
    }

    private VpnRuntimeStatusView view(VpnControlStatus control, VpnProxyHealth health) {
        return vpnRuntimeViewService.from(control, health);
    }

    private FlashNotification notification(String action, boolean running) {
        return switch (action) {
            case "connect" -> FlashNotification.success(
                    running ? "VPN connection started." : "VPN connection start requested."
            );
            case "disconnect" -> FlashNotification.success("VPN connection stopped.");
            case "reconnect" -> FlashNotification.success(
                    running ? "VPN reconnection requested." : "VPN reconnect command sent."
            );
            default -> FlashNotification.info("VPN control command completed.");
        };
    }

    private void recordControl(
            String action,
            boolean success,
            String message,
            HttpServletRequest request,
            VpnRuntimeStatusView view
    ) {
        activityLogService.record(
                "VPN_CONTROL",
                request,
                null,
                null,
                success,
                message,
                view == null
                        ? Map.of("action", action)
                        : Map.of(
                                "action", action,
                                "state", view.state(),
                                "activeVpnTasks", Integer.toString(view.activeVpnTasks())
                        )
        );
    }

    private String redirectToVpn() {
        return "redirect:" + VPN_PATH;
    }
}
