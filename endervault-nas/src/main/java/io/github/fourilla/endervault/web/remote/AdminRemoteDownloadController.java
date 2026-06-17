package io.github.fourilla.endervault.web.remote;

import io.github.fourilla.endervault.remote.RemoteDownloadProbe;
import io.github.fourilla.endervault.remote.RemoteDownloadService;
import io.github.fourilla.endervault.remote.RemoteDownloadTask;
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

    public AdminRemoteDownloadController(RemoteDownloadService remoteDownloadService) {
        this.remoteDownloadService = remoteDownloadService;
    }

    @GetMapping("/files/remote-download")
    public String remoteDownload(Model model) {
        model.addAttribute("tasks", remoteDownloadService.listTasks());
        return "remote-download";
    }

    @PostMapping("/files/remote-download/inspect")
    public Object inspect(
            @RequestParam("url") String url,
            @RequestParam(value = "path", required = false) String path,
            HttpServletRequest request,
            Model model
    ) throws IOException {
        RemoteDownloadProbe probe = remoteDownloadService.inspect(url, path);
        if (ActionResponseSupport.wantsJson(request)) {
            return ResponseEntity.ok(RemoteDownloadInspectResponse.ok(RemoteDownloadProbePayload.from(probe)));
        }

        model.addAttribute("probe", probe);
        model.addAttribute("url", url);
        model.addAttribute("path", path);
        return "remote-download-confirm";
    }

    @PostMapping("/files/remote-download")
    public Object start(
            @RequestParam("url") String url,
            @RequestParam(value = "path", required = false) String path,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        RemoteDownloadTask task = remoteDownloadService.start(url, path, request);
        FlashNotification notification = FlashNotification.success("Remote download queued (" + task.shortId() + ").");
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                "redirect:/files/remote-download",
                RemoteDownloadActionResponse.ok(notification, RemoteDownloadTaskPayload.from(task))
        );
    }

    @PostMapping("/files/remote-download/cancel")
    public Object cancel(
            @RequestParam("id") String id,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        RemoteDownloadTask task = remoteDownloadService.cancel(id);
        FlashNotification notification = FlashNotification.success("Remote download canceled (" + task.shortId() + ").");
        return ActionResponseSupport.ok(request, redirectAttributes, notification, "redirect:/files/remote-download");
    }

    @PostMapping("/files/remote-download/delete")
    public Object delete(
            @RequestParam("id") String id,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        remoteDownloadService.deleteTask(id);
        FlashNotification notification = FlashNotification.success("Remote download task removed.");
        return ActionResponseSupport.ok(request, redirectAttributes, notification, "redirect:/files/remote-download");
    }

    @GetMapping(value = "/files/remote-download/tasks", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<RemoteDownloadTaskPayload> tasks() {
        return remoteDownloadService.listTasks().stream()
                .map(RemoteDownloadTaskPayload::from)
                .toList();
    }

}
