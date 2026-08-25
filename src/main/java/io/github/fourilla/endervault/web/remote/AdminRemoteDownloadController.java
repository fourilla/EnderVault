package io.github.fourilla.endervault.web.remote;

import io.github.fourilla.endervault.remote.RemoteDownloadService;
import io.github.fourilla.endervault.config.NasProperties;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminRemoteDownloadController {

    private final RemoteDownloadService remoteDownloadService;
    private final NasProperties nasProperties;

    public AdminRemoteDownloadController(
            RemoteDownloadService remoteDownloadService,
            NasProperties nasProperties
    ) {
        this.remoteDownloadService = remoteDownloadService;
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
        model.addAttribute(
                "defaultTargetDirectory",
                nasProperties.getRemoteDownload().getDefaultTargetDirectory()
        );
        return "remote-download";
    }
}
