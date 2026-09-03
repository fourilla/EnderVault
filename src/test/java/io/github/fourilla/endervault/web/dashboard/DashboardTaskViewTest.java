package io.github.fourilla.endervault.web.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.task.TaskType;
import org.junit.jupiter.api.Test;

class DashboardTaskViewTest {

    @Test
    void normalizesApplicationTaskWithStableActivityIdentity() {
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
        assertThat(view.id()).isEqualTo("server-application-task-id");
        assertThat(view.status()).isEqualTo("queued");
        assertThat(view.active()).isTrue();
    }

    @Test
    void summaryDoesNotExposeTaskOwnerOrClientAddress() {
        AppTask task = new AppTask(
                "application-task-id", TaskType.FILE_COPY, "Copy selected items",
                "private-path", "private-owner", "192.0.2.123"
        );

        DashboardTaskView view = DashboardTaskView.from(task);

        assertThat(view.toString()).doesNotContain("private-owner", "192.0.2.123", "private-path");
    }
}
