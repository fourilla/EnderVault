package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.filetool.archive.ArchiveExtractionTaskService;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.task.TaskActionResponse;
import io.github.fourilla.endervault.web.task.TaskPayload;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriUtils;

@Controller
public class AdminArchiveController {

    private final ArchiveExtractionTaskService archiveExtractionTaskService;

    public AdminArchiveController(ArchiveExtractionTaskService archiveExtractionTaskService) {
        this.archiveExtractionTaskService = archiveExtractionTaskService;
    }

    @PostMapping("/files/detail/archive/extract")
    public Object extract(
            @RequestParam("path") String path,
            @RequestParam(value = "destinationPath", required = false) String destinationPath,
            @RequestParam(value = "outputName", required = false) String outputName,
            @RequestParam(value = "createContainingDirectory", defaultValue = "false")
            boolean createContainingDirectory,
            @RequestParam(value = "conflictPolicy", defaultValue = "cancel") String conflictPolicy,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        AppTask task = archiveExtractionTaskService.queue(
                path,
                destinationPath == null ? "" : destinationPath,
                outputName,
                createContainingDirectory,
                ConflictPolicy.from(conflictPolicy),
                request
        );
        FlashNotification notification = FlashNotification.info("Archive extraction queued.");
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                redirectToDetail(path),
                TaskActionResponse.ok(notification, TaskPayload.from(task))
        );
    }

    private String redirectToDetail(String path) {
        return "redirect:/files/detail?path=" + UriUtils.encodeQueryParam(path, StandardCharsets.UTF_8);
    }
}
