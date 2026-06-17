package io.github.fourilla.endervault.activity;

@FunctionalInterface
public interface ActivityLogNotifier {

    ActivityLogNotifier NOOP = entry -> {
    };

    void notify(ActivityLogEntry entry);
}
