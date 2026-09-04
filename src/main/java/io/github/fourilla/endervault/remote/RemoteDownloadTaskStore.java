package io.github.fourilla.endervault.remote;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class RemoteDownloadTaskStore {

    private final int historyLimit;
    private final Map<String, RemoteDownloadTask> tasks = new ConcurrentHashMap<>();

    public RemoteDownloadTaskStore(NasProperties nasProperties) {
        this.historyLimit = Math.max(1, nasProperties.getRemoteDownload().getHistoryLimit());
    }

    public void add(RemoteDownloadTask task) {
        tasks.put(task.id(), task);
        trimHistory();
    }

    public List<RemoteDownloadTask> list() {
        return tasks.values().stream()
                .sorted(Comparator.comparing(RemoteDownloadTask::createdAt).reversed())
                .toList();
    }

    public Optional<RemoteDownloadTask> find(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    public RemoteDownloadTask require(String id) {
        return find(id).orElseThrow(() -> new StorageAccessException("Remote download task was not found."));
    }

    public void remove(String id) {
        tasks.remove(id);
    }

    private void trimHistory() {
        List<RemoteDownloadTask> allTasks = list();
        if (allTasks.size() <= historyLimit) {
            return;
        }
        allTasks.stream()
                .skip(historyLimit)
                .filter(task -> !task.active())
                .map(RemoteDownloadTask::id)
                .forEach(tasks::remove);
    }
}
