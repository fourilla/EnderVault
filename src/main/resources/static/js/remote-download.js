document.addEventListener("DOMContentLoaded", () => {
    const form = document.getElementById("remoteDownloadForm");
    const dialog = document.getElementById("remoteConfirmDialog");
    const tasksSection = document.getElementById("remoteTasksSection");
    const tasksBody = document.getElementById("remoteTasksBody");
    const tasksHeading = document.getElementById("remoteTasksHeading");
    const tasksEmpty = document.getElementById("remoteTasksEmpty");
    const pollIntervalMs = 1400;
    let pendingStartData = null;
    const { requestJson, cloneFormData, showNotification } = window.EnderVault;

    const setFormBusy = (busy) => {
        if (!form) {
            return;
        }
        form.querySelectorAll("button, input").forEach((control) => {
            if (control.type === "hidden") {
                return;
            }
            control.disabled = busy;
        });
    };

    const setDialogBusy = (busy) => {
        dialog?.querySelectorAll("button").forEach((button) => {
            button.disabled = busy;
        });
    };

    const closeDialog = () => {
        pendingStartData = null;
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

        const warning = dialog?.querySelector("[data-remote-warning]");
        if (warning) {
            warning.hidden = !probe.warningLabel;
            warning.textContent = probe.warningLabel || "";
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
                pendingStartData = cloneFormData(formData);
                if (!showProbe(payload.probe)) {
                    pendingStartData = null;
                }
            } catch (error) {
                showNotification({ type: "error", message: error.message || "Remote file inspection failed." });
            } finally {
                setFormBusy(false);
            }
        });
    }

    dialog?.querySelectorAll("[data-remote-confirm-close]").forEach((button) => {
        button.addEventListener("click", closeDialog);
    });

    dialog?.querySelector("[data-remote-confirm-start]")?.addEventListener("click", async () => {
        if (!form || !pendingStartData) {
            return;
        }
        setDialogBusy(true);
        try {
            const payload = await requestJson(form.dataset.startUrl, {
                method: "POST",
                body: cloneFormData(pendingStartData)
            });
            showNotification(payload.notification);
            closeDialog();
            await refreshTasks();
            form.reset();
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
        const badge = document.createElement("span");
        badge.className = `status-badge ${task.statusClass}`;
        badge.textContent = task.cancelRequested && task.active ? "Canceling" : task.statusLabel;
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

    const actionCell = (task) => {
        const cell = document.createElement("td");
        const actions = document.createElement("div");
        actions.className = "table-actions";
        actions.append(taskActionButton(task));
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

        tasks.forEach((task) => {
            const row = document.createElement("tr");
            appendCell(row, task.shortId);
            const source = appendCell(row, task.sourceUrl, "remote-source");
            source.title = task.sourceUrl;
            appendCell(row, task.targetPath || task.targetDirectory);
            row.append(statusCell(task));
            row.append(progressCell(task));
            appendCell(row, task.createdLabel);
            appendCell(row, task.finishedLabel);
            appendCell(row, task.message, "remote-message");
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

    tasksBody?.addEventListener("click", async (event) => {
        const button = event.target.closest("[data-remote-action]");
        if (!button || button.disabled) {
            return;
        }
        event.preventDefault();
        button.disabled = true;
        try {
            await postTaskAction(button.dataset.remoteAction, button.dataset.taskId);
        } catch (error) {
            showNotification({ type: "error", message: error.message || "The task action failed." });
            button.disabled = false;
        }
    });

    if (tasksSection?.dataset.tasksUrl) {
        window.setInterval(() => {
            if (document.visibilityState === "visible") {
                refreshTasks().catch(() => {
                    // Keep polling; a transient refresh failure should not break the page.
                });
            }
        }, pollIntervalMs);
    }
});
