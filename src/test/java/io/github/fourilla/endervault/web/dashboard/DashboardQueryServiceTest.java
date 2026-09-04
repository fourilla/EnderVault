package io.github.fourilla.endervault.web.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.fourilla.endervault.filerequest.FileRequest;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.filerequest.UploaderNamePolicy;
import io.github.fourilla.endervault.metadata.MetadataInspectionReport;
import io.github.fourilla.endervault.metadata.MetadataInspectionReportStore;
import io.github.fourilla.endervault.session.SessionManagementService;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.task.TaskManagerService;
import io.github.fourilla.endervault.task.TaskType;
import io.github.fourilla.endervault.thumbnail.ThumbnailCacheStats;
import io.github.fourilla.endervault.thumbnail.ThumbnailService;
import io.github.fourilla.endervault.trash.TrashService;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DashboardQueryServiceTest {

    @Test
    void summarizesWithoutExposingRequestTokensAndReusesExpensiveCacheScan() throws Exception {
        ThumbnailService thumbnails = mock(ThumbnailService.class);
        FileRequestService requests = mock(FileRequestService.class);
        MetadataInspectionReportStore reports = mock(MetadataInspectionReportStore.class);
        TaskManagerService tasks = mock(TaskManagerService.class);
        when(thumbnails.cacheStats()).thenReturn(new ThumbnailCacheStats(true, true, true, 5, 100, "100 B", 0));
        when(reports.latest()).thenReturn(MetadataInspectionReport.empty());
        Instant now = Instant.now();
        FileRequest active = new FileRequest("id", "private-token", "Private title", "", "private/path",
                UploaderNamePolicy.OPTIONAL, 100, 1000, 10, List.of(), 0, 0, List.of(), now, null, true);
        FileRequest full = active.withAcceptedUpload("upload", 1000);
        when(requests.list()).thenReturn(List.of(active, active.revoke(), full));
        when(tasks.listTasks()).thenReturn(IntStream.range(0, 8).mapToObj(index -> new AppTask(
                "id-" + index, TaskType.FILE_COPY, "Copy", "files", "admin", "127.0.0.1")).toList());
        DashboardQueryService service = new DashboardQueryService(mock(StorageService.class),
                mock(TrashService.class), mock(ShareLinkService.class), thumbnails, requests, reports,
                tasks, mock(SessionManagementService.class));

        DashboardView view = service.query();
        assertThat(view.fileRequests().active()).isEqualTo(1);
        assertThat(view.fileRequests().total()).isEqualTo(3);
        assertThat(view.inspection().present()).isFalse();
        assertThat(view.serverTasks()).hasSize(8).allMatch(DashboardTaskView::active);
        assertThat(view.toString()).doesNotContain("private-token", "Private title", "private/path");
        service.query();
        verify(thumbnails, times(1)).cacheStats();
    }
}
