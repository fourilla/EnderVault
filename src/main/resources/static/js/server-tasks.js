(function () {
    const trackedStorageKey = "endervault.trackedTasks";
    const pollIntervalMs = 1400;
    const refreshUrls = new Map();
    const terminalNotifications = new Set();
    const terminalEvents = new Set();
    let pollTimer = null;

    const metaContent = (name) =>
        document.querySelector(`meta[name="${name}"]`)?.content || "";

    const configuredBoolean = (name, fallback) => {
        const value = metaContent(name).trim().toLowerCase();
        if (value === "true") {
            return true;
        }
        if (value === "false") {
            return false;
        }
        return fallback;
    };

    const configuredInteger = (name, fallback) => {
        const value = Number.parseInt(metaContent(name), 10);
        return Number.isFinite(value) && value >= 0 ? value : fallback;
    };

    const panelEnabled = () => {
        if (!configuredBoolean("endervault-task-activity-enabled", true)) {
            return false;
        }
        if (document.body?.dataset.serverTaskActivity === "false") {
            return false;
        }
        return document.body?.dataset.serverTaskActivity === "true"
                || Boolean(document.querySelector(".app-shell"));
    };

    const trackedIds = () => {
        try {
            const raw = window.sessionStorage.getItem(trackedStorageKey);
            const parsed = raw ? JSON.parse(raw) : [];
            return Array.isArray(parsed) ? parsed.filter(Boolean) : [];
        } catch (error) {
            return [];
        }
    };

    const rememberTrackedIds = (ids) => {
        try {
            window.sessionStorage.setItem(trackedStorageKey, JSON.stringify(Array.from(new Set(ids))));
        } catch (error) {
            // Tracking is progressive enhancement only.
        }
    };

    const addTrackedId = (id) => rememberTrackedIds([...trackedIds(), id]);

    const removeTrackedId = (id) => rememberTrackedIds(trackedIds().filter((value) => value !== id));

    const csrfFormData = (id) => {
        const formData = new FormData();
        const csrf = window.EnderVault.csrfPair();
        if (csrf) {
            formData.append(csrf.name, csrf.value);
        }
        formData.append("id", id);
        return formData;
    };

    const terminal = (task) => !task.active;

    const panelId = (task) => `server-${task.id}`;

    const cancelTask = async (task) => {
        const body = await window.EnderVault.requestJson("/api/v1/tasks/cancel", {
            method: "POST",
            body: csrfFormData(task.id)
        });
        if (body.task) {
            renderTask(body.task);
        }
    };

    const maybeRefreshPage = (task) => {
        const refreshUrl = refreshUrls.get(task.id);
        if (!refreshUrl || !window.EnderVaultFileBrowser?.requestListingRefresh) {
            return;
        }
        refreshUrls.delete(task.id);
        window.EnderVaultFileBrowser.requestListingRefresh(refreshUrl);
    };

    const maybeNotifyTerminalTask = (task) => {
        if (task.type !== "METADATA_INSPECTION" || terminalNotifications.has(task.id)) {
            return;
        }
        terminalNotifications.add(task.id);
        const status = (task.status || "").toUpperCase();
        if (status === "COMPLETE" || status === "PARTIAL") {
            window.EnderVault?.showToast?.(
                "success",
                "Metadata inspection completed. Check the inspector page for the latest report.",
                {
                    actionLabel: "Open inspector",
                    actionHref: "/admin/metadata"
                }
            );
            return;
        }
        if (status === "CANCELED") {
            window.EnderVault?.showToast?.("info", "Metadata inspection canceled.");
            return;
        }
        if (status === "FAILED") {
            window.EnderVault?.showToast?.("warning", task.message || "Metadata inspection did not complete.");
        }
    };

    const renderTask = (task) => {
        if (!panelEnabled()) {
            return;
        }

        const id = panelId(task);
        window.EnderVaultActivity?.upsert({
            id,
            title: task.title,
            type: task.type,
            typeLabel: task.typeLabel,
            status: task.status.toLowerCase(),
            percent: task.progressPercent,
            message: task.message || task.progressLabel,
            cancelRequested: task.cancelRequested,
            cancelable: task.active,
            onCancel: () => cancelTask(task)
        });

        if (terminal(task)) {
            if (!terminalEvents.has(task.id)) {
                terminalEvents.add(task.id);
                document.dispatchEvent(new CustomEvent("endervault:task-terminal", { detail: task }));
            }
            maybeRefreshPage(task);
            maybeNotifyTerminalTask(task);
            removeTrackedId(task.id);
            const delayMs = task.status === "COMPLETE"
                    ? configuredInteger("endervault-task-completed-display-ms", 2800)
                    : configuredInteger("endervault-task-failed-display-ms", 7000);
            window.EnderVaultActivity?.scheduleRemoval(id, delayMs);
        }
    };

    const refresh = async () => {
        if (!panelEnabled()) {
            stopPolling();
            return;
        }

        const ids = trackedIds();
        if (ids.length === 0) {
            stopPolling();
            return;
        }

        const params = new URLSearchParams();
        ids.forEach((id) => params.append("ids", id));
        const tasks = await window.EnderVault.requestJson(`/api/v1/tasks?${params.toString()}`);
        const returnedIds = new Set(tasks.map((task) => task.id));
        tasks.forEach(renderTask);
        ids.filter((id) => !returnedIds.has(id)).forEach((id) => {
            removeTrackedId(id);
            window.EnderVaultActivity?.remove(`server-${id}`);
        });
    };

    function startPolling() {
        if (pollTimer) {
            return;
        }
        pollTimer = window.setInterval(() => {
            if (document.visibilityState === "visible") {
                refresh().catch(() => {
                    // A transient polling failure should not break the page.
                });
            }
        }, pollIntervalMs);
    }

    function stopPolling() {
        if (!pollTimer) {
            return;
        }
        window.clearInterval(pollTimer);
        pollTimer = null;
    }

    const track = (task, options = {}) => {
        if (!task?.id) {
            return;
        }
        addTrackedId(task.id);
        if (options.refreshUrl) {
            refreshUrls.set(task.id, options.refreshUrl);
        }
        if (!panelEnabled()) {
            return;
        }
        renderTask(task);
        if (options.announceStart) {
            window.EnderVaultActivity?.announceStarted([`server-${task.id}`]);
        }
        startPolling();
    };

    document.addEventListener("DOMContentLoaded", () => {
        if (panelEnabled() && trackedIds().length > 0) {
            startPolling();
            refresh().catch(() => {
                // Initial refresh is best effort.
            });
        }
    });

    window.EnderVaultServerTasks = {
        track,
        refresh
    };
})();
