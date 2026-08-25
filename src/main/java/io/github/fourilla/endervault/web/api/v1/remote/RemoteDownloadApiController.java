package io.github.fourilla.endervault.web.api.v1.remote;

import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.outbound.OutboundRouteStateService;
import io.github.fourilla.endervault.remote.RemoteDownloadInspection;
import io.github.fourilla.endervault.remote.RemoteDownloadService;
import io.github.fourilla.endervault.remote.RemoteDownloadTask;
import io.github.fourilla.endervault.web.remote.RemoteDownloadActionResponse;
import io.github.fourilla.endervault.web.remote.RemoteDownloadInspectResponse;
import io.github.fourilla.endervault.web.remote.RemoteDownloadProbePayload;
import io.github.fourilla.endervault.web.remote.RemoteDownloadTaskPayload;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/remote-downloads")
public class RemoteDownloadApiController {

    private final RemoteDownloadService remoteDownloadService;
    private final OutboundRouteStateService outboundRouteStateService;

    public RemoteDownloadApiController(
            RemoteDownloadService remoteDownloadService,
            OutboundRouteStateService outboundRouteStateService
    ) {
        this.remoteDownloadService = remoteDownloadService;
        this.outboundRouteStateService = outboundRouteStateService;
    }

    @PostMapping("/inspect")
    public RemoteDownloadInspectResponse inspect(
            @RequestParam(value = "url", required = false) String url,
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "networkRoute", defaultValue = "global") String networkRoute,
            @RequestParam(value = "connections", defaultValue = "1") int connections,
            @RequestParam(value = "skipInspection", defaultValue = "false") boolean skipInspection,
            @RequestParam(value = "customHeaders", required = false) String customHeaders,
            HttpServletRequest request
    ) throws IOException {
        RemoteDownloadInspection inspection = remoteDownloadService.inspect(
                url,
                path,
                resolveRoute(networkRoute),
                connections,
                skipInspection,
                customHeaders,
                request
        );
        return RemoteDownloadInspectResponse.ok(
                inspection.requestId(),
                RemoteDownloadProbePayload.from(inspection.probe())
        );
    }

    @PostMapping
    public RemoteDownloadActionResponse start(
            @RequestParam("requestId") String requestId,
            HttpServletRequest request
    ) throws IOException {
        RemoteDownloadTask task = remoteDownloadService.start(requestId, request);
        FlashNotification notification = FlashNotification.success("Remote download queued (" + task.shortId() + ").");
        return RemoteDownloadActionResponse.ok(notification, RemoteDownloadTaskPayload.from(task));
    }

    @PostMapping("/inspect/discard")
    public Map<String, Boolean> discardInspection(
            @RequestParam("requestId") String requestId,
            HttpServletRequest request
    ) {
        return Map.of("discarded", remoteDownloadService.discardInspection(requestId, request));
    }

    @PostMapping("/cancel")
    public RemoteDownloadActionResponse cancel(@RequestParam("id") String id) {
        RemoteDownloadTask task = remoteDownloadService.cancel(id);
        FlashNotification notification = FlashNotification.success("Remote download canceled (" + task.shortId() + ").");
        return RemoteDownloadActionResponse.ok(notification, RemoteDownloadTaskPayload.from(task));
    }

    @PostMapping("/delete")
    public RemoteDownloadActionResponse delete(@RequestParam("id") String id) {
        remoteDownloadService.deleteTask(id);
        return RemoteDownloadActionResponse.ok(FlashNotification.success("Remote download task removed."));
    }

    @GetMapping(value = "/tasks", produces = MediaType.APPLICATION_JSON_VALUE)
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
