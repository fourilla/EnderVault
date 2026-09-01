(function () {
    const terminalStatuses = new Set(["pending", "complete", "partial", "failed", "canceled"]);
    const state = {
        items: new Map()
    };

    const formatBytes = (bytes) => {
        if (!Number.isFinite(bytes) || bytes <= 0) {
            return "0 B";
        }

        const units = ["B", "KB", "MB", "GB", "TB"];
        let value = bytes;
        let unitIndex = 0;
        while (value >= 1024 && unitIndex < units.length - 1) {
            value /= 1024;
            unitIndex += 1;
        }
        const precision = value >= 10 || unitIndex === 0 ? 0 : 1;
        return `${value.toFixed(precision)} ${units[unitIndex]}`;
    };

    const normalizeStatus = (status) => {
        const normalized = (status || "running").toLowerCase();
        return normalized === "queued" ? "uploading" : normalized;
    };

    const terminal = (item) => terminalStatuses.has(normalizeStatus(item.status));
    const activeItems = () => Array.from(state.items.values()).filter((item) => !terminal(item));
    const terminalItems = () => Array.from(state.items.values()).filter(terminal);

    const publicItem = (item) => ({
        id: item.id,
        title: item.title || item.id,
        type: item.type || "TASK",
        typeLabel: item.typeLabel || "Task",
        status: normalizeStatus(item.status),
        percent: Number.isFinite(item.percent)
                ? Math.max(0, Math.min(100, Math.round(item.percent)))
                : terminal(item) ? 100 : 0,
        message: item.cancelRequested && !terminal(item)
                ? "Canceling..."
                : item.message || item.typeLabel || "",
        cancelRequested: Boolean(item.cancelRequested),
        cancelable: Boolean(item.cancelable) && !terminal(item)
    });

    const snapshot = () => ({
        activeCount: activeItems().length,
        finishedCount: terminalItems().length,
        totalCount: state.items.size,
        items: Array.from(state.items.values()).map(publicItem)
    });

    const notifyChanged = () => {
        document.dispatchEvent(new CustomEvent("endervault:activity-changed", {
            detail: snapshot()
        }));
    };

    const upsert = (item) => {
        if (!item?.id) {
            return null;
        }
        const current = state.items.get(item.id) || {};
        const next = { ...current, ...item };
        state.items.set(next.id, next);
        notifyChanged();
        return publicItem(next);
    };

    const remove = (id) => {
        const item = state.items.get(id);
        window.clearTimeout(item?.removeTimer);
        state.items.delete(id);
        notifyChanged();
    };

    const scheduleRemoval = (id, delayMs = 6000) => {
        const item = state.items.get(id);
        if (!item) {
            return;
        }
        window.clearTimeout(item.removeTimer);
        item.removeTimer = window.setTimeout(() => remove(id), delayMs);
    };

    const cancel = async (id) => {
        const item = state.items.get(id);
        if (!item || !item.cancelable || item.cancelRequested || terminal(item)) {
            return;
        }

        item.cancelRequested = true;
        item.message = "Canceling...";
        notifyChanged();
        try {
            await item.onCancel?.(item);
        } catch (error) {
            item.cancelRequested = false;
            item.message = error.message || "Cancel failed.";
            notifyChanged();
        }
    };

    window.EnderVaultActivity = {
        upsert,
        remove,
        scheduleRemoval,
        cancel,
        formatBytes,
        snapshot
    };
})();
