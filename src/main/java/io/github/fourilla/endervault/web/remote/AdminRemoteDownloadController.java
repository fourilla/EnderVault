package io.github.fourilla.endervault.web.remote;

import io.github.fourilla.endervault.remote.RemoteDownloadInspection;
import io.github.fourilla.endervault.remote.RemoteDownloadService;
import io.github.fourilla.endervault.remote.RemoteDownloadTask;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.outbound.OutboundRouteStateService;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminRemoteDownloadController {

    private final RemoteDownloadService remoteDownloadService;
    private final OutboundRouteStateService outboundRouteStateService;
    private final NasProperties nasProperties;

    public AdminRemoteDownloadController(
            RemoteDownloadService remoteDownloadService,
            OutboundRouteStateService outboundRouteStateService,
            NasProperties nasProperties
    ) {
        this.remoteDownloadService = remoteDownloadService;
        this.outboundRouteStateService = outboundRouteStateService;
        this.nasProperties = nasProperties;
    }

    private static final String REMOTE_DOWNLOAD_PATH = "/admin/utils/remote-download";

    @GetMapping(REMOTE_DOWNLOAD_PATH)
    public String remoteDownload(Model model) {
        model.addAttribute("tasks", remoteDownloadService.listTasks());
        model.addAttribute(
                "skipInspectByDefault",
                nasProperties.getRemoteDownload().isSkipInspectByDefault()
        );
        return "remote-download";
    }

    @PostMapping(REMOTE_DOWNLOAD_PATH + "/inspect")
    public Object inspect(
            @RequestParam(value = "url", required = false) String url,
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "networkRoute", defaultValue = "global") String networkRoute,
            @RequestParam(value = "connections", defaultValue = "1") int connections,
            @RequestParam(value = "conflictPolicy", defaultValue = "default") String conflictPolicy,
            @RequestParam(value = "skipInspection", defaultValue = "false") boolean skipInspection,
            @RequestParam(value = "customHeaders", required = false) String customHeaders,
            HttpServletRequest request,
            Model model
    ) throws IOException {
        RemoteDownloadInspection inspection = remoteDownloadService.inspect(
                url,
                path,
                resolveRoute(networkRoute),
                connections,
                conflictPolicy,
                skipInspection,
                customHeaders,
                request
        );
        if (ActionResponseSupport.wantsJson(request)) {
            return ResponseEntity.ok(RemoteDownloadInspectResponse.ok(
                    inspection.requestId(),
                    RemoteDownloadProbePayload.from(inspection.probe())
            ));
        }

        model.addAttribute("probe", inspection.probe());
        model.addAttribute("requestId", inspection.requestId());
        return "remote-download-confirm";
    }

    @PostMapping(REMOTE_DOWNLOAD_PATH)
    public Object start(
            @RequestParam("requestId") String requestId,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        RemoteDownloadTask task = remoteDownloadService.start(requestId, request);
        FlashNotification notification = FlashNotification.success("Remote download queued (" + task.shortId() + ").");
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                "redirect:" + REMOTE_DOWNLOAD_PATH,
                RemoteDownloadActionResponse.ok(notification, RemoteDownloadTaskPayload.from(task))
        );
    }

    @PostMapping(REMOTE_DOWNLOAD_PATH + "/cancel")
    public Object cancel(
            @RequestParam("id") String id,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        RemoteDownloadTask task = remoteDownloadService.cancel(id);
        FlashNotification notification = FlashNotification.success("Remote download canceled (" + task.shortId() + ").");
        return ActionResponseSupport.ok(request, redirectAttributes, notification, "redirect:" + REMOTE_DOWNLOAD_PATH);
    }

    @PostMapping(REMOTE_DOWNLOAD_PATH + "/delete")
    public Object delete(
            @RequestParam("id") String id,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        remoteDownloadService.deleteTask(id);
        FlashNotification notification = FlashNotification.success("Remote download task removed.");
        return ActionResponseSupport.ok(request, redirectAttributes, notification, "redirect:" + REMOTE_DOWNLOAD_PATH);
    }

    @GetMapping(value = REMOTE_DOWNLOAD_PATH + "/tasks", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<RemoteDownloadTaskPayload> tasks() {
        return remoteDownloadService.listTasks().stream()
                .map(RemoteDownloadTaskPayload::from)
                .toList();
    }

    private NetworkRoute resolveRoute(String selection) {
        return selection == null || selection.isBlank() || "global".equalsIgnoreCase(selection)
                ? outboundRouteStateService.currentRoute()
                : NetworkRoute.fromSetting(selection);
    }
}
