package io.github.fourilla.endervault.web.remote;

import io.github.fourilla.endervault.web.support.AdminSpaViewService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminRemoteDownloadController {

    private final AdminSpaViewService adminSpaViewService;

    public AdminRemoteDownloadController(AdminSpaViewService adminSpaViewService) {
        this.adminSpaViewService = adminSpaViewService;
    }

    private static final String REMOTE_DOWNLOAD_PATH = "/admin/utils/remote-download";

    @GetMapping(REMOTE_DOWNLOAD_PATH)
    public String remoteDownload(Model model) {
        return adminSpaViewService.render(model);
    }
}
