package io.github.fourilla.endervault.web.dashboard;

import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.remote.RemoteDownloadTask;
import io.github.fourilla.endervault.task.AppTask;
import java.time.Instant;
import org.springframework.util.StringUtils;

public record DashboardTaskView(
        String title,
        String detail,
        String iconClass,
        String statusLabel,
        String statusClass,
        int progressPercent,
        String progressLabel,
        String routeLabel,
        String routeClass,
        String target,
        Instant createdAt
) {

    public static DashboardTaskView from(AppTask task) {
        return new DashboardTaskView(
                task.title(),
                task.type().label() + " #" + task.shortId(),
                task.type().iconClass(),
                task.statusLabel(),
                task.statusClass(),
                task.progressPercent(),
                task.progressLabel(),
                "Server",
                "expired",
                blankLabel(task.targetPath(), "No target path"),
                task.createdAt()
        );
    }

    public static DashboardTaskView from(RemoteDownloadTask task) {
        String title = StringUtils.hasText(task.fileName())
                ? task.fileName()
                : "Remote download #" + task.shortId();
        String target = StringUtils.hasText(task.targetPath())
                ? task.targetPath()
                : blankLabel(task.targetDirectory(), "Vault root");
        return new DashboardTaskView(
                title,
                "Remote download #" + task.shortId(),
                "fas fa-cloud-arrow-down",
                task.statusLabel(),
                task.statusClass(),
                task.progressPercent(),
                task.progressLabel(),
                task.networkRouteLabel(),
                task.networkRoute() == NetworkRoute.VPN_REQUIRED ? "active" : "info",
                target,
                task.createdAt()
        );
    }

    private static String blankLabel(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
