package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.filetool.archive.ArchiveCreationTaskService;
import io.github.fourilla.endervault.filetool.archive.ArchiveExtractionTaskService;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.SelectedItems;
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
import org.springframework.web.util.UriComponentsBuilder;

@Controller
public class AdminArchiveController {

    private final ArchiveCreationTaskService archiveCreationTaskService;
    private final ArchiveExtractionTaskService archiveExtractionTaskService;

    public AdminArchiveController(
            ArchiveCreationTaskService archiveCreationTaskService,
            ArchiveExtractionTaskService archiveExtractionTaskService
    ) {
        this.archiveCreationTaskService = archiveCreationTaskService;
        this.archiveExtractionTaskService = archiveExtractionTaskService;
    }

    @PostMapping("/files/archive/create")
    public Object create(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "outputName", required = false) String outputName,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        AppTask task = archiveCreationTaskService.queue(
                path,
                SelectedItems.from(request),
                outputName,
                request
        );
        FlashNotification notification = FlashNotification.info("ZIP creation queued.");
        String redirect = redirectToFiles(path);
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                redirect,
                new ArchiveCreationActionResponse(
                        true,
                        notification,
                        TaskPayload.from(task),
                        ActionResponseSupport.redirectUrl(redirect)
                )
        );
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

    private String redirectToFiles(String path) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/files");
        if (path != null && !path.isBlank()) {
            builder.queryParam("path", path);
        }
        return "redirect:" + builder.build().encode().toUriString();
    }

    private record ArchiveCreationActionResponse(
            boolean ok,
            FlashNotification notification,
            TaskPayload task,
            String redirectUrl
    ) {
    }
}
