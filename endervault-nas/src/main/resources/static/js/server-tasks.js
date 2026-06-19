(function () {
    const trackedStorageKey = "endervault.trackedTasks";
    const pollIntervalMs = 1400;
    const refreshUrls = new Map();
    let pollTimer = null;

    const panelEnabled = () => document.body?.dataset.serverTaskActivity === "true";

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
        const body = await window.EnderVault.requestJson("/admin/tasks/cancel", {
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
            maybeRefreshPage(task);
            removeTrackedId(task.id);
            window.EnderVaultActivity?.scheduleRemoval(id, task.status === "COMPLETE" ? 2800 : 7000);
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
        const tasks = await window.EnderVault.requestJson(`/admin/tasks?${params.toString()}`);
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
