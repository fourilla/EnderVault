document.addEventListener("DOMContentLoaded", () => {
    const form = document.getElementById("remoteDownloadForm");
    const dialog = document.getElementById("remoteConfirmDialog");
    const tasksSection = document.getElementById("remoteTasksSection");
    const tasksBody = document.getElementById("remoteTasksBody");
    const tasksHeading = document.getElementById("remoteTasksHeading");
    const tasksEmpty = document.getElementById("remoteTasksEmpty");
    const taskDialog = document.getElementById("remoteTaskDialog");
    const urlInput = form?.querySelector('input[name="url"]');
    const customHeadersInput = form?.querySelector('textarea[name="customHeaders"]');
    const curlImportInput = document.getElementById("remoteCurlImport");
    const curlImportButton = form?.querySelector("[data-remote-import-curl]");
    const connectionsInput = form?.querySelector('input[name="connections"]');
    const skipInspectionInput = form?.querySelector('input[name="skipInspection"]');
    const pollIntervalMs = 1400;
    let pendingRequestId = null;
    let rememberedConnections = connectionsInput?.value || "1";
    const taskSnapshots = new Map();
    const { askConfirmation, requestJson, showNotification } = window.EnderVault;

    const syncInspectionControls = () => {
        if (!connectionsInput || !skipInspectionInput) {
            return;
        }
        if (skipInspectionInput.checked) {
            connectionsInput.value = "1";
            connectionsInput.disabled = true;
            connectionsInput.title = "Skipped inspection supports one connection only.";
        } else {
            connectionsInput.disabled = false;
            connectionsInput.removeAttribute("title");
        }
    };

    const importCurl = async () => {
        if (!curlImportInput || !urlInput || !customHeadersInput || !window.EnderVaultRemoteCurl) {
            return;
        }

        let imported;
        try {
            imported = window.EnderVaultRemoteCurl.parse(curlImportInput.value);
        } catch (error) {
            showNotification({ type: "error", message: error.message || "The cURL command could not be imported." });
            return;
        }

        const currentUrl = urlInput.value.trim();
        const currentHeaders = customHeadersInput.value.trim();
        const changesExistingValues = (currentUrl && currentUrl !== imported.url)
                || (currentHeaders && currentHeaders !== imported.customHeaders);
        if (changesExistingValues) {
            const replace = await askConfirmation({
                title: "Replace request fields?",
                message: "Importing this cURL command will replace the current URL and custom headers.",
                confirmLabel: "Replace"
            });
            if (!replace) {
                return;
            }
        }

        urlInput.value = imported.url;
        customHeadersInput.value = imported.customHeaders;
        curlImportInput.value = "";
        urlInput.dispatchEvent(new Event("change", { bubbles: true }));
        customHeadersInput.dispatchEvent(new Event("change", { bubbles: true }));
        showNotification({
            type: "success",
            message: imported.customHeaders ? "URL and custom headers imported." : "URL imported."
        });
        urlInput.focus();
    };

    const setFormBusy = (busy) => {
        if (!form) {
            return;
        }
        form.querySelectorAll("button, input, select, textarea").forEach((control) => {
            if (control.type === "hidden") {
                return;
            }
            control.disabled = busy;
        });
        if (!busy) {
            syncInspectionControls();
        }
    };

    const setDialogBusy = (busy) => {
        dialog?.querySelectorAll("button").forEach((button) => {
            button.disabled = busy;
        });
    };

    const closeDialog = () => {
        pendingRequestId = null;
        if (!dialog) {
            return;
        }
        if (typeof dialog.close === "function" && dialog.open) {
            dialog.close();
        } else {
            dialog.hidden = true;
        }
    };

    const text = (selector, value) => {
        const target = dialog?.querySelector(selector);
        if (target) {
            target.textContent = value || "-";
        }
    };

    const showProbe = (probe) => {
        text('[data-remote-probe="fileName"]', probe.fileName);
        text('[data-remote-probe="targetPath"]', probe.targetPath);
        text('[data-remote-probe="contentLengthLabel"]', probe.contentLengthLabel);
        text('[data-remote-probe="contentTypeLabel"]', probe.contentTypeLabel);
        text('[data-remote-probe="finalUrl"]', probe.finalUrl);
        text('[data-remote-probe="networkRouteLabel"]', probe.networkRouteLabel);
        text('[data-remote-probe="statusLabel"]', probe.statusLabel);
        text('[data-remote-probe="rangeCapabilityLabel"]', probe.rangeCapabilityLabel);
        text('[data-remote-probe="requestOptionsLabel"]', probe.requestOptionsLabel);
        text('[data-remote-probe="conflictPolicyLabel"]', probe.conflictPolicyLabel);
        text('[data-remote-probe="detail"]', probe.detail);

        const warning = dialog?.querySelector("[data-remote-warning]");
        if (warning) {
            warning.hidden = !probe.warningLabel;
            warning.textContent = probe.warningLabel || "";
        }

        const startButton = dialog?.querySelector("[data-remote-confirm-start]");
        if (startButton) {
            startButton.disabled = !probe.startAllowed;
        }

        if (!dialog) {
            return window.confirm(`Start remote download?\n\n${probe.fileName}\n${probe.contentLengthLabel}\n${probe.contentTypeLabel}`);
        }

        if (typeof dialog.showModal === "function") {
            dialog.showModal();
        } else {
            dialog.hidden = false;
        }
        return true;
    };

    if (form) {
        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            const formData = new FormData(form);
            setFormBusy(true);
            try {
                const payload = await requestJson(form.action, {
                    method: (form.method || "POST").toUpperCase(),
                    body: formData
                });
                pendingRequestId = payload.requestId;
                if (!showProbe(payload.probe)) {
                    pendingRequestId = null;
                }
            } catch (error) {
                showNotification({ type: "error", message: error.message || "Remote file inspection failed." });
            } finally {
                setFormBusy(false);
            }
        });
    }

    curlImportButton?.addEventListener("click", importCurl);

    dialog?.querySelectorAll("[data-remote-confirm-close]").forEach((button) => {
        button.addEventListener("click", closeDialog);
    });
    dialog?.addEventListener("close", () => {
        pendingRequestId = null;
    });

    dialog?.querySelector("[data-remote-confirm-start]")?.addEventListener("click", async () => {
        if (!form || !pendingRequestId) {
            return;
        }
        setDialogBusy(true);
        try {
            const startData = new FormData();
            const csrf = window.EnderVault.csrfPair(form);
            if (csrf) {
                startData.append(csrf.name, csrf.value);
            }
            startData.append("requestId", pendingRequestId);
            const payload = await requestJson(form.dataset.startUrl, {
                method: "POST",
                body: startData
            });
            showNotification(payload.notification);
            closeDialog();
            await refreshTasks();
            form.reset();
            rememberedConnections = connectionsInput?.defaultValue || "1";
            syncInspectionControls();
        } catch (error) {
            showNotification({ type: "error", message: error.message || "Remote download could not be started." });
        } finally {
            setDialogBusy(false);
        }
    });

    const appendCell = (row, value, className = "") => {
        const cell = document.createElement("td");
        if (className) {
            cell.className = className;
        }
        cell.textContent = value || "";
        row.append(cell);
        return cell;
    };

    const progressCell = (task) => {
        const cell = document.createElement("td");
        const wrapper = document.createElement("div");
        wrapper.className = "remote-progress";
        wrapper.innerHTML = `
            <div class="upload-progress-track" role="progressbar" aria-valuemin="0" aria-valuemax="100">
                <div class="upload-progress-bar"></div>
            </div>
            <span></span>
        `;
        wrapper.querySelector(".upload-progress-track").setAttribute("aria-valuenow", String(task.progressPercent));
        wrapper.querySelector(".upload-progress-bar").style.width = `${task.progressPercent}%`;
        wrapper.querySelector("span").textContent = task.progressLabel;
        cell.append(wrapper);
        return cell;
    };

    const statusCell = (task) => {
        const cell = document.createElement("td");
        const wrapper = document.createElement("div");
        wrapper.className = "remote-status-progress";
        const badge = document.createElement("span");
        badge.className = `status-badge ${task.statusClass}`;
        badge.textContent = task.cancelRequested && task.active ? "Canceling" : task.statusLabel;
        wrapper.append(badge, progressCell(task).firstElementChild);
        cell.append(wrapper);
        return cell;
    };

    const routeCell = (task) => {
        const cell = document.createElement("td");
        const badge = document.createElement("span");
        badge.className = `status-badge ${task.networkRoute === "vpn-required" ? "active" : "info"}`;
        badge.textContent = task.networkRouteLabel;
        cell.append(badge);
        return cell;
    };

    const taskActionButton = (task) => {
        const button = document.createElement("button");
        const action = task.active ? "cancel" : "delete";
        button.type = "button";
        button.className = `${task.active ? "danger" : "ghost"} icon-button action-icon`;
        button.dataset.remoteAction = action;
        button.dataset.taskId = task.id;
        button.title = task.active ? "Cancel" : "Remove task";
        button.setAttribute("aria-label", button.title);
        button.innerHTML = task.active
                ? '<i class="fas fa-ban" aria-hidden="true"></i>'
                : '<i class="fas fa-trash-can" aria-hidden="true"></i>';
        if (task.cancelRequested && task.active) {
            button.disabled = true;
        }
        return button;
    };

    const taskDetailsButton = (task) => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "ghost icon-button action-icon";
        button.dataset.remoteAction = "details";
        button.dataset.taskId = task.id;
        button.title = "Details";
        button.setAttribute("aria-label", button.title);
        button.innerHTML = '<i class="fas fa-circle-info" aria-hidden="true"></i>';
        return button;
    };

    const actionCell = (task) => {
        const cell = document.createElement("td");
        const actions = document.createElement("div");
        actions.className = "table-actions";
        actions.append(taskDetailsButton(task), taskActionButton(task));
        cell.append(actions);
        return cell;
    };

    const renderTasks = (tasks) => {
        if (!tasksSection || !tasksBody || !tasksHeading || !tasksEmpty) {
            return;
        }

        tasksHeading.textContent = `Tasks (${tasks.length})`;
        tasksSection.hidden = tasks.length === 0;
        tasksEmpty.hidden = tasks.length > 0;
        tasksBody.replaceChildren();
        taskSnapshots.clear();

        tasks.forEach((task) => {
            taskSnapshots.set(task.id, task);
            const row = document.createElement("tr");
            const source = document.createElement("td");
            source.className = "remote-source";
            source.title = task.sourceUrl;
            const sourceName = document.createElement("strong");
            sourceName.textContent = task.fileName || `Remote download #${task.shortId}`;
            const sourceUrl = document.createElement("small");
            sourceUrl.textContent = task.sourceUrl;
            source.append(sourceName, sourceUrl);
            row.append(source);
            appendCell(row, task.targetPath || task.targetDirectory);
            row.append(routeCell(task));
            row.append(statusCell(task));
            appendCell(row, task.createdLabel);
            row.append(actionCell(task));
            tasksBody.append(row);
        });
    };

    async function refreshTasks() {
        if (!tasksSection?.dataset.tasksUrl) {
            return;
        }
        const tasks = await requestJson(tasksSection.dataset.tasksUrl);
        renderTasks(tasks);
    }

    const postTaskAction = async (action, taskId) => {
        const url = action === "cancel" ? tasksSection?.dataset.cancelUrl : tasksSection?.dataset.deleteUrl;
        if (!url || !taskId) {
            return;
        }
        const formData = new FormData();
        const csrf = window.EnderVault.csrfPair(form);
        if (csrf) {
            formData.append(csrf.name, csrf.value);
        }
        formData.append("id", taskId);

        const payload = await requestJson(url, {
            method: "POST",
            body: formData
        });
        showNotification(payload.notification);
        await refreshTasks();
    };

    const closeTaskDialog = () => {
        if (typeof taskDialog?.close === "function" && taskDialog.open) {
            taskDialog.close();
        } else if (taskDialog) {
            taskDialog.hidden = true;
        }
    };

    const showTaskDetails = (task) => {
        if (!taskDialog || !task) {
            return;
        }
        const values = {
            id: `Task ${task.id}`,
            sourceUrl: task.sourceUrl,
            target: task.targetPath || task.targetDirectory || "/",
            networkRouteLabel: task.networkRouteLabel,
            conflictPolicyLabel: task.conflictPolicyLabel,
            connections: task.actualConnections > 0
                    ? `${task.actualConnections} active / ${task.requestedConnections} requested`
                    : `Pending / ${task.requestedConnections} requested`,
            retryCount: String(task.retryCount),
            createdLabel: task.createdLabel,
            startedLabel: task.startedLabel,
            finishedLabel: task.finishedLabel,
            message: task.message
        };
        Object.entries(values).forEach(([key, value]) => {
            const target = taskDialog.querySelector(`[data-remote-task-detail="${key}"]`);
            if (target) {
                target.textContent = value || "-";
            }
        });
        if (typeof taskDialog.showModal === "function") {
            taskDialog.showModal();
        } else {
            taskDialog.hidden = false;
        }
    };

    taskDialog?.querySelector("[data-remote-task-close]")?.addEventListener("click", closeTaskDialog);
    taskDialog?.addEventListener("click", (event) => {
        if (event.target === taskDialog) {
            closeTaskDialog();
        }
    });

    skipInspectionInput?.addEventListener("change", () => {
        if (skipInspectionInput.checked && connectionsInput) {
            rememberedConnections = connectionsInput.value || "1";
        }
        syncInspectionControls();
        if (!skipInspectionInput.checked && connectionsInput) {
            connectionsInput.value = rememberedConnections;
        }
    });
    syncInspectionControls();

    tasksBody?.addEventListener("click", async (event) => {
        const button = event.target.closest("[data-remote-action]");
        if (!button || button.disabled) {
            return;
        }
        event.preventDefault();
        if (button.dataset.remoteAction === "details") {
            showTaskDetails(taskSnapshots.get(button.dataset.taskId));
            return;
        }
        button.disabled = true;
        try {
            await postTaskAction(button.dataset.remoteAction, button.dataset.taskId);
        } catch (error) {
            showNotification({ type: "error", message: error.message || "The task action failed." });
            button.disabled = false;
        }
    });

    if (tasksSection?.dataset.tasksUrl) {
        refreshTasks().catch(() => {
            // The server-rendered table remains usable if the initial refresh fails.
        });
        window.setInterval(() => {
            if (document.visibilityState === "visible") {
                refreshTasks().catch(() => {
                    // Keep polling; a transient refresh failure should not break the page.
                });
            }
        }, pollIntervalMs);
    }
});
