(function () {
    const terminalStatuses = new Set(["pending", "complete", "partial", "failed", "canceled"]);
    const state = {
        items: new Map(),
        minimized: false,
        panel: null,
        list: null,
        summary: null,
        toggleButton: null
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

    const activeItems = () =>
        Array.from(state.items.values()).filter((item) => !terminalStatuses.has(normalizeStatus(item.status)));

    const terminalItems = () =>
        Array.from(state.items.values()).filter((item) => terminalStatuses.has(normalizeStatus(item.status)));

    const snapshot = () => ({
        activeCount: activeItems().length,
        finishedCount: terminalItems().length,
        totalCount: state.items.size,
        minimized: state.minimized
    });

    const notifyChanged = () => {
        document.dispatchEvent(new CustomEvent("endervault:activity-changed", {
            detail: snapshot()
        }));
    };

    const ensurePanel = () => {
        if (state.panel) {
            state.panel.hidden = false;
            return;
        }

        const panel = document.createElement("section");
        panel.className = "upload-activity js-only";
        panel.setAttribute("aria-label", "Activity");
        panel.hidden = true;
        panel.innerHTML = `
            <header class="upload-activity-header">
                <div class="upload-activity-heading">
                    <i class="fas fa-list-check" aria-hidden="true"></i>
                    <div>
                        <strong>Activity</strong>
                        <span class="upload-activity-summary">Preparing...</span>
                    </div>
                </div>
                <button class="ghost icon-button upload-activity-toggle" type="button"
                        title="Minimize activity" aria-label="Minimize activity">
                    <i class="fas fa-minus" aria-hidden="true"></i>
                </button>
            </header>
            <div class="upload-activity-body">
                <div class="upload-list"></div>
            </div>
        `;
        document.body.append(panel);

        state.panel = panel;
        state.list = panel.querySelector(".upload-list");
        state.summary = panel.querySelector(".upload-activity-summary");
        state.toggleButton = panel.querySelector(".upload-activity-toggle");
        state.toggleButton.addEventListener("click", () => {
            state.minimized = !state.minimized;
            render();
        });
    };

    const updatePanelMetrics = (visible) => {
        document.body.classList.toggle("activity-panel-visible", visible);
        if (!visible || !state.panel) {
            document.body.style.removeProperty("--activity-panel-height");
            return;
        }

        window.requestAnimationFrame(() => {
            if (!state.panel || state.panel.hidden) {
                return;
            }
            document.body.style.setProperty("--activity-panel-height", `${state.panel.offsetHeight}px`);
        });
    };

    const progressPercent = (item) => {
        if (Number.isFinite(item.percent)) {
            return Math.max(0, Math.min(100, Math.round(item.percent)));
        }
        return terminalStatuses.has(normalizeStatus(item.status)) ? 100 : 0;
    };

    const statusText = (item) => {
        if (item.cancelRequested && !terminalStatuses.has(normalizeStatus(item.status))) {
            return "Canceling...";
        }
        return item.message || item.typeLabel || "";
    };

    const rowClass = (item) => {
        const status = normalizeStatus(item.status);
        if (status === "complete" || status === "partial") {
            return "upload-complete";
        }
        if (status === "pending") {
            return "upload-pending";
        }
        if (status === "failed") {
            return "upload-failed";
        }
        if (status === "canceled") {
            return "upload-canceled";
        }
        return "upload-uploading";
    };

    const cancelItem = async (item) => {
        if (!item.cancelable || item.cancelRequested || terminalStatuses.has(normalizeStatus(item.status))) {
            return;
        }

        item.cancelRequested = true;
        item.message = "Canceling...";
        render();
        try {
            await item.onCancel?.(item);
        } catch (error) {
            item.cancelRequested = false;
            item.message = error.message || "Cancel failed.";
            render();
        }
    };

    const ensureRow = (item) => {
        if (item.row) {
            return item.row;
        }

        const element = document.createElement("article");
        element.className = "upload-item upload-uploading";
        element.innerHTML = `
            <div class="upload-item-main">
                <div class="upload-item-row">
                    <span class="upload-name"></span>
                    <span class="upload-percent">0%</span>
                </div>
                <div class="upload-meta"></div>
                <div class="upload-progress-track" role="progressbar"
                     aria-valuemin="0" aria-valuemax="100" aria-valuenow="0">
                    <div class="upload-progress-bar" style="width: 0%"></div>
                </div>
            </div>
        `;

        const cancelButton = document.createElement("button");
        cancelButton.className = "ghost icon-button upload-cancel";
        cancelButton.type = "button";
        cancelButton.innerHTML = '<i class="fas fa-xmark" aria-hidden="true"></i>';
        cancelButton.addEventListener("click", () => cancelItem(item));
        element.append(cancelButton);

        item.row = element;
        item.nameElement = element.querySelector(".upload-name");
        item.percentElement = element.querySelector(".upload-percent");
        item.metaElement = element.querySelector(".upload-meta");
        item.progressTrack = element.querySelector(".upload-progress-track");
        item.progressBar = element.querySelector(".upload-progress-bar");
        item.cancelButton = cancelButton;
        return element;
    };

    const updateRow = (item) => {
        const row = ensureRow(item);
        const percent = progressPercent(item);
        const status = normalizeStatus(item.status);
        row.className = `upload-item ${rowClass(item)}`;
        item.nameElement.textContent = item.title || item.id;
        item.nameElement.title = item.title || item.id;
        item.percentElement.textContent = `${percent}%`;
        item.metaElement.textContent = statusText(item);
        item.progressTrack.setAttribute("aria-valuenow", String(percent));
        item.progressBar.style.width = `${percent}%`;

        const cancelable = item.cancelable && !item.cancelRequested && !terminalStatuses.has(status);
        item.cancelButton.hidden = !item.cancelable || terminalStatuses.has(status);
        item.cancelButton.disabled = !cancelable;
        item.cancelButton.title = item.cancelRequested ? "Canceling" : "Cancel";
        item.cancelButton.setAttribute("aria-label", `Cancel ${item.title || item.id}`);
    };

    function render() {
        ensurePanel();

        const items = Array.from(state.items.values());
        const activeCount = activeItems().length;
        const doneCount = terminalItems().length;
        if (items.length === 0) {
            state.panel.hidden = true;
            updatePanelMetrics(false);
            notifyChanged();
            return;
        }

        state.panel.hidden = false;
        state.panel.classList.toggle("is-minimized", state.minimized);
        state.summary.textContent = activeCount > 0
            ? `${activeCount} running - ${doneCount} finished`
            : `${doneCount} finished`;
        state.toggleButton.title = state.minimized ? "Show activity" : "Minimize activity";
        state.toggleButton.setAttribute("aria-label", state.toggleButton.title);
        state.toggleButton.querySelector("i").className =
            state.minimized ? "fas fa-chevron-up" : "fas fa-minus";

        items.forEach((item) => {
            const row = ensureRow(item);
            if (row.parentElement !== state.list) {
                state.list.append(row);
            }
            updateRow(item);
        });
        updatePanelMetrics(true);
        notifyChanged();
    }

    const show = () => {
        if (state.items.size === 0) {
            return;
        }
        state.minimized = false;
        render();
    };

    const toggle = () => {
        if (state.items.size === 0) {
            return;
        }
        state.minimized = !state.minimized;
        render();
    };

    const scheduleRemoval = (id, delayMs = 6000) => {
        const item = state.items.get(id);
        if (!item) {
            return;
        }
        window.clearTimeout(item.removeTimer);
        item.removeTimer = window.setTimeout(() => remove(id), delayMs);
    };

    const upsert = (item) => {
        if (!item?.id) {
            return null;
        }
        ensurePanel();
        const current = state.items.get(item.id) || {};
        const next = { ...current, ...item };
        state.items.set(next.id, next);
        render();
        return next;
    };

    const remove = (id) => {
        const item = state.items.get(id);
        item?.row?.remove();
        window.clearTimeout(item?.removeTimer);
        state.items.delete(id);
        render();
    };

    window.EnderVaultActivity = {
        upsert,
        remove,
        scheduleRemoval,
        render,
        formatBytes,
        snapshot,
        show,
        toggle
    };
})();
