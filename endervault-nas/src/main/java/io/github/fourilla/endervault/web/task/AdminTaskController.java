package io.github.fourilla.endervault.web.task;

import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.task.TaskManagerService;
import io.github.fourilla.endervault.web.support.FlashNotification;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminTaskController {

    private final TaskManagerService taskManagerService;

    public AdminTaskController(TaskManagerService taskManagerService) {
        this.taskManagerService = taskManagerService;
    }

    @GetMapping(value = "/admin/tasks", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TaskPayload> tasks(@RequestParam(value = "ids", required = false) List<String> ids) {
        return taskManagerService.listTasks(ids).stream()
                .map(TaskPayload::from)
                .toList();
    }

    @PostMapping(value = "/admin/tasks/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TaskActionResponse> cancel(@RequestParam("id") String id) {
        try {
            AppTask task = taskManagerService.cancel(id);
            return ResponseEntity.ok(TaskActionResponse.ok(
                    FlashNotification.info("Task cancellation requested."),
                    TaskPayload.from(task)
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(TaskActionResponse.error(ex.getMessage()));
        }
    }

    @PostMapping(value = "/admin/tasks/delete", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TaskActionResponse> delete(@RequestParam("id") String id) {
        try {
            taskManagerService.delete(id);
            return ResponseEntity.ok(TaskActionResponse.ok(FlashNotification.success("Task removed."), null));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(TaskActionResponse.error(ex.getMessage()));
        }
    }
}
