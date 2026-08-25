package io.github.fourilla.endervault.web.api.v1.file;

import static io.github.fourilla.endervault.web.file.FileRedirects.filesUrl;

import io.github.fourilla.endervault.filetool.archive.ArchiveCreationTaskService;
import io.github.fourilla.endervault.filetool.archive.ArchiveExtractionTaskService;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.SelectedItems;
import io.github.fourilla.endervault.web.task.TaskActionResponse;
import io.github.fourilla.endervault.web.task.TaskPayload;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/files/archives")
public class ArchiveApiController {

    private final ArchiveCreationTaskService archiveCreationTaskService;
    private final ArchiveExtractionTaskService archiveExtractionTaskService;

    public ArchiveApiController(
            ArchiveCreationTaskService archiveCreationTaskService,
            ArchiveExtractionTaskService archiveExtractionTaskService
    ) {
        this.archiveCreationTaskService = archiveCreationTaskService;
        this.archiveExtractionTaskService = archiveExtractionTaskService;
    }

    @PostMapping
    public ArchiveCreationActionResponse create(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "outputName", required = false) String outputName,
            HttpServletRequest request
    ) throws IOException {
        AppTask task = archiveCreationTaskService.queue(
                path,
                SelectedItems.from(request),
                outputName,
                request
        );
        FlashNotification notification = FlashNotification.info("ZIP creation queued.");
        return new ArchiveCreationActionResponse(
                true,
                notification,
                TaskPayload.from(task),
                filesUrl(path)
        );
    }

    @PostMapping("/extract")
    public TaskActionResponse extract(
            @RequestParam("path") String path,
            @RequestParam(value = "destinationPath", required = false) String destinationPath,
            @RequestParam(value = "outputName", required = false) String outputName,
            @RequestParam(value = "createContainingDirectory", defaultValue = "false")
            boolean createContainingDirectory,
            @RequestParam(value = "conflictPolicy", defaultValue = "cancel") String conflictPolicy,
            HttpServletRequest request
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
        return TaskActionResponse.ok(notification, TaskPayload.from(task));
    }

    private record ArchiveCreationActionResponse(
            boolean ok,
            FlashNotification notification,
            TaskPayload task,
            String redirectUrl
    ) {
    }
}
