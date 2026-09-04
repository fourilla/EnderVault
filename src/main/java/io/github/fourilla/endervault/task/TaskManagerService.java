package io.github.fourilla.endervault.task;

import io.github.fourilla.endervault.config.NasProperties;
import jakarta.annotation.PreDestroy;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.stereotype.Service;

@Service
public class TaskManagerService {

    private final NasProperties.Tasks taskProperties;
    private final ExecutorService executorService;
    private final Map<String, AppTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> taskFutures = new ConcurrentHashMap<>();

    public TaskManagerService(NasProperties nasProperties) {
        this.taskProperties = nasProperties.getTasks();
        this.executorService = Executors.newFixedThreadPool(Math.max(1, taskProperties.getWorkerThreads()));
    }

    public AppTask submit(TaskType type, String title, String targetPath, String actor, String ip, TaskWork work) {
        AppTask task = new AppTask(UUID.randomUUID().toString(), type, title, targetPath, actor, ip);
        tasks.put(task.id(), task);
        trimHistory();
        Future<?> future = executorService.submit(() -> run(task, work));
        taskFutures.put(task.id(), future);
        if (!task.active()) {
            taskFutures.remove(task.id(), future);
        }
        return task;
    }

    public List<AppTask> listTasks() {
        return tasks.values().stream()
                .sorted(Comparator.comparing(AppTask::createdAt).reversed())
                .toList();
    }

    public List<AppTask> listTasks(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return listTasks();
        }
        return ids.stream()
                .map(tasks::get)
                .filter(task -> task != null)
                .sorted(Comparator.comparing(AppTask::createdAt).reversed())
                .toList();
    }

    public List<AppTask> activeTasks(TaskType type) {
        return tasks.values().stream()
                .filter(AppTask::active)
                .filter(task -> task.type() == type)
                .sorted(Comparator.comparing(AppTask::createdAt).reversed())
                .toList();
    }

    public AppTask newestActiveTask(TaskType type) {
        return activeTasks(type).stream().findFirst().orElse(null);
    }

    public int requestCancelActive(TaskType type) {
        int canceled = 0;
        for (AppTask task : activeTasks(type)) {
            if (task.requestCancel()) {
                Future<?> future = taskFutures.get(task.id());
                boolean futureCanceled = future != null && future.cancel(true);
                if (task.status() == TaskStatus.QUEUED && futureCanceled) {
                    task.markCanceled("Canceled before it started.");
                    taskFutures.remove(task.id());
                }
                canceled++;
            }
        }
        return canceled;
    }

    public TaskSummary summary() {
        List<AppTask> allTasks = listTasks();
        long running = allTasks.stream().filter(AppTask::active).count();
        long complete = allTasks.stream().filter(task -> task.status() == TaskStatus.COMPLETE).count();
        long failed = allTasks.stream()
                .filter(task -> task.status() == TaskStatus.FAILED || task.status() == TaskStatus.PARTIAL)
                .count();
        return new TaskSummary(allTasks.size(), running, complete, failed);
    }

    public AppTask cancel(String id) {
        AppTask task = requireTask(id);
        if (!task.requestCancel()) {
            throw new IllegalArgumentException("Only queued or running tasks can be canceled.");
        }
        Future<?> future = taskFutures.get(task.id());
        boolean futureCanceled = future != null && future.cancel(true);
        if (task.status() == TaskStatus.QUEUED && futureCanceled) {
            task.markCanceled("Canceled before it started.");
            taskFutures.remove(task.id());
        }
        return task;
    }

    public void delete(String id) {
        AppTask task = requireTask(id);
        if (task.active()) {
            throw new IllegalArgumentException("Running tasks cannot be deleted.");
        }
        tasks.remove(task.id());
        taskFutures.remove(task.id());
    }

    public AppTask resolvePending(String id, boolean discarded, String committedPath) {
        AppTask task = tasks.get(id);
        if (task == null || task.status() != TaskStatus.PENDING) {
            return task;
        }
        if (discarded) {
            task.markCanceled("Pending file discarded.");
        } else {
            if (committedPath == null || committedPath.isBlank()) {
                throw new IllegalArgumentException("Committed path is required when resolving a pending task.");
            }
            task.setTargetPath(committedPath);
            task.markComplete("Saved to " + committedPath + ".");
        }
        return task;
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }

    private void run(AppTask task, TaskWork work) {
        try {
            if (task.cancelRequested()) {
                throw new TaskCanceledException();
            }
            task.markRunning();
            TaskOutcome outcome = work.run(new TaskContext(task));
            TaskOutcome safeOutcome = outcome == null ? TaskOutcome.complete("Complete.") : outcome;
            switch (safeOutcome.status()) {
                case PENDING -> task.markPending(safeOutcome.message());
                case PARTIAL -> task.markPartial(safeOutcome.message());
                default -> task.markComplete(safeOutcome.message());
            }
        } catch (TaskCanceledException ex) {
            task.markCanceled("Canceled.");
        } catch (Exception ex) {
            task.markFailed(cleanMessage(ex));
        } finally {
            taskFutures.remove(task.id());
        }
    }

    private AppTask requireTask(String id) {
        AppTask task = tasks.get(id);
        if (task == null) {
            throw new IllegalArgumentException("Task was not found.");
        }
        return task;
    }

    private void trimHistory() {
        List<AppTask> allTasks = listTasks();
        int historyLimit = Math.max(1, taskProperties.getHistoryLimit());
        if (allTasks.size() <= historyLimit) {
            return;
        }
        allTasks.stream()
                .skip(historyLimit)
                .filter(task -> !task.active())
                .map(AppTask::id)
                .forEach(tasks::remove);
    }

    private String cleanMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? "Task failed." : message;
    }
}
