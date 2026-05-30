package io.github.fourilla.endervault.remote;

import java.util.List;

public record RemoteDownloadSummary(
        long total,
        long running,
        long complete,
        long failed,
        List<RemoteDownloadTask> recentTasks
) {
}
