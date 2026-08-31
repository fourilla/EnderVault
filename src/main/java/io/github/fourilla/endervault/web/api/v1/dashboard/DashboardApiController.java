package io.github.fourilla.endervault.web.api.v1.dashboard;

import io.github.fourilla.endervault.web.dashboard.DashboardQueryService;
import io.github.fourilla.endervault.web.dashboard.DashboardView;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardApiController {

    private final DashboardQueryService dashboardQueryService;

    public DashboardApiController(DashboardQueryService dashboardQueryService) {
        this.dashboardQueryService = dashboardQueryService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public DashboardView dashboard() throws IOException {
        return dashboardQueryService.query();
    }
}
