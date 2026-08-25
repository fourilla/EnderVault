package io.github.fourilla.endervault.web.api.v1.activitylog;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import java.io.IOException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/activity-logs")
public class ActivityLogApiController {

    private final ActivityLogService activityLogService;

    public ActivityLogApiController(ActivityLogService activityLogService) {
        this.activityLogService = activityLogService;
    }

    @PostMapping("/delete")
    public ActionResponse deleteArchive(@RequestParam("file") String fileName) throws IOException {
        activityLogService.deleteArchive(fileName);
        return ActionResponse.redirect(
                FlashNotification.success("Activity log deleted."),
                "/admin/logs"
        );
    }
}
