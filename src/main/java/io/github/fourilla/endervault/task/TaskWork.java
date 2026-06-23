package io.github.fourilla.endervault.task;

@FunctionalInterface
public interface TaskWork {

    TaskOutcome run(TaskContext context) throws Exception;
}
