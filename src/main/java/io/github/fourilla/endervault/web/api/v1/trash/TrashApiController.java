package io.github.fourilla.endervault.web.api.v1.trash;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.trash.TrashRecord;
import io.github.fourilla.endervault.trash.TrashSearchSchema;
import io.github.fourilla.endervault.trash.TrashService;
import io.github.fourilla.endervault.trash.TrashService.TrashRestoreResult;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.SelectedItemActions;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trash")
public class TrashApiController {

    private final TrashService trashService;
    private final ActivityLogService activityLogService;

    public TrashApiController(TrashService trashService, ActivityLogService activityLogService) {
        this.trashService = trashService;
        this.activityLogService = activityLogService;
    }

    @GetMapping
    public TrashBrowserPayload list(@RequestParam(value = "q", required = false) String query) throws IOException {
        var filter = TrashSearchSchema.compile(query);
        return TrashBrowserPayload.from(trashService.list().stream().filter(filter).toList());
    }

    @PostMapping("/restore")
    public ActionResponse restore(
            @RequestParam("id") String id,
            HttpServletRequest request
    ) throws IOException {
        TrashRestoreResult result = trashService.restore(id);
        TrashRecord record = result.record();
        activityLogService.record(
                "TRASH_RESTORE",
                request,
                record.originalPath(),
                result.restoredPath(),
                "Restored item from trash"
        );
        return ActionResponse.ok(FlashNotification.success("Item restored."));
    }

    @PostMapping("/delete")
    public ActionResponse delete(
            @RequestParam("id") String id,
            HttpServletRequest request
    ) throws IOException {
        TrashRecord record = trashService.deletePermanently(id);
        activityLogService.record(
                "TRASH_DELETE",
                request,
                record.originalPath(),
                null,
                "Permanently deleted trash item"
        );
        return ActionResponse.ok(FlashNotification.success("Item permanently deleted."));
    }

    @PostMapping("/empty")
    public ActionResponse empty(HttpServletRequest request) throws IOException {
        int deletedCount = trashService.empty();
        activityLogService.record(
                "TRASH_EMPTY",
                request,
                null,
                null,
                "Emptied trash (" + deletedCount + " item(s))"
        );
        return ActionResponse.ok(FlashNotification.success(
                "Emptied trash (" + deletedCount + " item(s))."
        ));
    }

    @PostMapping("/selected/resolve")
    public ResponseEntity<?> resolveSelected(
            @RequestParam(value = "ids", required = false) List<String> ids,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "confirmed", defaultValue = "false") boolean confirmed,
            HttpServletRequest request) {
        try {
            var selected = SelectedItemActions.validate(ids, action, "DELETE", confirmed, SelectedItemActions::canonicalUuid);
            return ResponseEntity.ok(SelectedItemActions.execute(selected, "Trash items",
                    id -> delete(id, request).notification().message()));
        } catch (StorageAccessException ex) {
            return ResponseEntity.badRequest().body(ActionResponse.error(ex.getMessage()));
        }
    }
}
