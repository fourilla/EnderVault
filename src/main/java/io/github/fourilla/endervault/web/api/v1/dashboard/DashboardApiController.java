package io.github.fourilla.endervault.web.api.v1.dashboard;

import io.github.fourilla.endervault.web.dashboard.DashboardQueryService;
import io.github.fourilla.endervault.web.dashboard.DashboardView;
import io.github.fourilla.endervault.web.dashboard.RuntimeResourceService;
import java.io.IOException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardApiController {

    private final DashboardQueryService dashboardQueryService;
    private final RuntimeResourceService runtimeResourceService;

    public DashboardApiController(DashboardQueryService dashboardQueryService, RuntimeResourceService runtimeResourceService) {
        this.dashboardQueryService = dashboardQueryService;
        this.runtimeResourceService = runtimeResourceService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DashboardView> dashboard() throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(dashboardQueryService.query());
    }

    @GetMapping(value = "/runtime", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RuntimeResourceService.Snapshot> runtime() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(runtimeResourceService.snapshot());
    }
}
