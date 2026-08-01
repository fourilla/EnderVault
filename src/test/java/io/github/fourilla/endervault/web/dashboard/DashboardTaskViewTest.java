package io.github.fourilla.endervault.web.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.remote.RemoteDownloadTask;
import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.task.TaskType;
import org.junit.jupiter.api.Test;

class DashboardTaskViewTest {

    @Test
    void normalizesApplicationTaskForDashboardTable() {
        AppTask task = new AppTask(
                "application-task-id",
                TaskType.FILE_COPY,
                "Copy selected items",
                "archive",
                "admin",
                "127.0.0.1"
        );

        DashboardTaskView view = DashboardTaskView.from(task);

        assertThat(view.title()).isEqualTo("Copy selected items");
        assertThat(view.detail()).contains("File copy").contains("#applicat");
        assertThat(view.routeLabel()).isEqualTo("Server");
        assertThat(view.target()).isEqualTo("archive");
    }

    @Test
    void normalizesVpnRemoteDownloadWithoutExposingSourceUrl() {
        RemoteDownloadTask task = new RemoteDownloadTask(
                "remote-task-id",
                "https://example.com/private/file.bin?token=secret",
                "incoming",
                NetworkRoute.VPN_REQUIRED,
                "admin",
                "127.0.0.1"
        );

        DashboardTaskView view = DashboardTaskView.from(task);

        assertThat(view.title()).isEqualTo("Remote download #remote-t");
        assertThat(view.routeLabel()).isEqualTo("VPN required");
        assertThat(view.routeClass()).isEqualTo("active");
        assertThat(view.target()).isEqualTo("incoming");
        assertThat(view.toString()).doesNotContain("token=secret");
    }
}
