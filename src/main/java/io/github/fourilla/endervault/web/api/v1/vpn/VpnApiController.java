package io.github.fourilla.endervault.web.api.v1.vpn;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealth;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthService;
import io.github.fourilla.endervault.outbound.vpn.control.VpnControlException;
import io.github.fourilla.endervault.outbound.vpn.control.VpnControlService;
import io.github.fourilla.endervault.outbound.vpn.control.VpnControlStatus;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.VpnRuntimeStatusView;
import io.github.fourilla.endervault.web.vpn.VpnRuntimeViewService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vpn")
public class VpnApiController {

    private final VpnControlService vpnControlService;
    private final VpnProxyHealthService vpnProxyHealthService;
    private final VpnRuntimeViewService vpnRuntimeViewService;
    private final ActivityLogService activityLogService;

    public VpnApiController(
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

    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public VpnRuntimeStatusView status() {
        return vpnRuntimeViewService.refresh();
    }

    @PostMapping("/refresh")
    public ResponseEntity<VpnControlActionResponse> refresh() {
        VpnRuntimeStatusView view = vpnRuntimeViewService.refresh();
        FlashNotification notification = FlashNotification.success("VPN status refreshed.");
        return ResponseEntity.ok(VpnControlActionResponse.ok(notification, view));
    }

    @PostMapping("/connect")
    public ResponseEntity<VpnControlActionResponse> connect(HttpServletRequest request) {
        return control("connect", false, request);
    }

    @PostMapping("/disconnect")
    public ResponseEntity<VpnControlActionResponse> disconnect(
            @RequestParam(defaultValue = "false") boolean force,
            HttpServletRequest request
    ) {
        return control("disconnect", force, request);
    }

    @PostMapping("/reconnect")
    public ResponseEntity<VpnControlActionResponse> reconnect(
            @RequestParam(defaultValue = "false") boolean force,
            HttpServletRequest request
    ) {
        return control("reconnect", force, request);
    }

    private ResponseEntity<VpnControlActionResponse> control(
            String action,
            boolean force,
            HttpServletRequest request
    ) {
        int activeVpnTasks = vpnRuntimeViewService.activeVpnTasks();
        if (!action.equals("connect") && activeVpnTasks > 0 && !force) {
            FlashNotification notification = FlashNotification.warning(
                    activeVpnTasks + " VPN remote download task(s) are active. Confirm the action to continue."
            );
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(VpnControlActionResponse.error(notification));
        }

        try {
            VpnControlStatus status = switch (action) {
                case "connect" -> vpnControlService.connect();
                case "disconnect" -> vpnControlService.disconnect();
                case "reconnect" -> vpnControlService.reconnect();
                default -> throw new IllegalArgumentException("Unsupported VPN control action.");
            };
            VpnProxyHealth health = vpnProxyHealthService.refresh();
            VpnRuntimeStatusView view = vpnRuntimeViewService.from(status, health);
            FlashNotification notification = notification(action, status.running());
            recordControl(action, true, notification.message(), request, view);
            return ResponseEntity.ok(VpnControlActionResponse.ok(notification, view));
        } catch (VpnControlException | IllegalArgumentException ex) {
            recordControl(action, false, ex.getMessage(), request, null);
            return ResponseEntity.badRequest()
                    .body(VpnControlActionResponse.error(FlashNotification.error(ex.getMessage())));
        }
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
}
