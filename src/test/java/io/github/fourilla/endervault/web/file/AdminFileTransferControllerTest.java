package io.github.fourilla.endervault.web.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.filetool.FileToolDescriptor;
import io.github.fourilla.endervault.filetool.FileToolService;
import io.github.fourilla.endervault.filetool.FileToolType;
import io.github.fourilla.endervault.filetool.text.TextFileService;
import io.github.fourilla.endervault.recent.RecentService;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.thumbnail.ThumbnailService;
import io.github.fourilla.endervault.web.support.FilePreviewSupport;
import io.github.fourilla.endervault.web.support.FileResponseService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.ResponseEntity;

class AdminFileTransferControllerTest {

    private StorageService storageService;
    private FileResponseService fileResponseService;
    private RecentService recentService;
    private FileToolService fileToolService;
    private AdminFileTransferController controller;

    @BeforeEach
    void setUp() {
        storageService = mock(StorageService.class);
        fileResponseService = mock(FileResponseService.class);
        recentService = mock(RecentService.class);
        fileToolService = mock(FileToolService.class);
        controller = new AdminFileTransferController(
                storageService,
                fileResponseService,
                recentService,
                mock(ThumbnailService.class),
                mock(ActivityLogService.class),
                fileToolService,
                mock(TextFileService.class),
                new FilePreviewSupport()
        );
    }

    @Test
    void rawRangePreviewDoesNotRecordRecentHistory() throws Exception {
        FileDetail detail = videoDetail();
        FileItem item = videoItem();
        Path file = Path.of("movie.mp4");
        HttpHeaders headers = new HttpHeaders();
        headers.setRange(List.of(HttpRange.createByteRange(0, 99)));

        when(storageService.describeVaultChild("media", "movie.mp4")).thenReturn(item);
        when(storageService.detail(StorageScope.VAULT, item.path())).thenReturn(detail);
        when(storageService.resolveVaultFile(detail.path())).thenReturn(file);
        when(fileToolService.resolve(detail)).thenReturn(FileToolDescriptor.of(FileToolType.VIDEO));
        when(fileResponseService.inline(eq(file), eq(headers))).thenReturn(ResponseEntity.ok().build());

        controller.preview("media", "movie.mp4", headers);
        controller.preview("media", "movie.mp4", headers);

        verify(recentService, never()).recordVaultPath(detail.path());
    }

    @Test
    void explicitOpenRecordsOnceThenRedirectsToRawPreview() throws Exception {
        FileDetail detail = videoDetail();
        when(storageService.detail(StorageScope.VAULT, detail.path())).thenReturn(detail);

        String redirect = controller.open(detail.path());

        assertThat(redirect).isEqualTo("redirect:/files/preview?path=media&item=movie.mp4");
        verify(recentService).recordVaultPath(detail.path());
    }

    private FileItem videoItem() {
        return new FileItem(
                "movie.mp4",
                "media/movie.mp4",
                false,
                1L,
                "1 B",
                "now",
                Instant.EPOCH,
                "video/mp4",
                true,
                true,
                false
        );
    }

    private FileDetail videoDetail() {
        return new FileDetail(
                "movie.mp4",
                "media/movie.mp4",
                "media",
                false,
                1L,
                "1 B",
                0L,
                "now",
                "now",
                "now",
                "video/mp4",
                "mp4",
                true,
                true,
                false
        );
    }
}
