package io.github.fourilla.endervault.directorytransfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.task.TaskManagerService;
import io.github.fourilla.endervault.task.TaskType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DirectoryTransferNotificationProviderTest {
    @Test void livePendingAndRunningTasksAreNotDuplicatedButOwnerCompletionIsRecoverable() throws Exception {
        var query = mock(DirectoryTransferQueryService.class);
        var tasks = mock(TaskManagerService.class);
        var pending = mock(PendingFileDecisionService.class);
        var live = mock(PendingFileDecision.class);
        when(live.id()).thenReturn("live");
        when(pending.list()).thenReturn(List.of(live));
        var running = mock(AppTask.class);
        when(running.resultReference()).thenReturn(id("running"));
        when(tasks.activeTasks(TaskType.DIRECTORY_MERGE)).thenReturn(List.of(running));
        when(query.unresolved()).thenReturn(List.of(
                summary("copy", DirectoryTransferPlan.Operation.COPY, null),
                summary("running", DirectoryTransferPlan.Operation.MOVE, null),
                summary("live", DirectoryTransferPlan.Operation.PENDING, DirectoryTransferRun.Phase.OWNER_COMPLETING),
                summary("removed", DirectoryTransferPlan.Operation.PENDING, DirectoryTransferRun.Phase.OWNER_COMPLETING),
                summary("orphan", DirectoryTransferPlan.Operation.PENDING, null)));
        var provider = new DirectoryTransferNotificationProvider(query, tasks, pending);
        var notifications = provider.items();
        assertThat(notifications).extracting(item -> item.id()).containsExactly("directory-merge-" + id("copy"), "directory-merge-" + id("removed"));
        assertThat(notifications.getFirst().href()).isEqualTo("/admin/pending-decisions#merge-" + id("copy"));
        assertThat(notifications.getFirst().target().kind()).isEqualTo(
                io.github.fourilla.endervault.notificationcenter.ActionRequiredItem.TargetKind.DIRECTORY_MERGE);
        assertThat(notifications.getFirst().target().id()).isEqualTo(id("copy"));
        assertThat(provider.reviewAllHref()).isEqualTo("/admin/pending-decisions");
    }

    private DirectoryTransferQueryService.Summary summary(String id, DirectoryTransferPlan.Operation operation, DirectoryTransferRun.Phase phase) {
        return new DirectoryTransferQueryService.Summary(id(id), operation, id, "target/photos", Instant.now(), 0, 2, 1,
                false, phase == null, phase == null ? null : new DirectoryTransferRun(id(id), 0, phase, false), null, phase == null, false);
    }

    private String id(String name) { return java.util.UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(); }
}
