document.addEventListener("DOMContentLoaded", () => {
    const { submitJsonForm, showNotification, showToast, navigateWithNotification, csrfPair } = window.EnderVault;
    const region = document.querySelector("[data-transfer-buffer-region]");
    const bulkForm = document.getElementById("bulkActionForm");

    if (!region) {
        return;
    }

    document.body.classList.add("transfer-buffer-dock-enabled");

    const minimizedStorageKey = "endervault.transferBuffer.minimized";

    const currentPath = () => region.dataset.currentPath || bulkForm?.querySelector('input[name="path"]')?.value || "";
    const returnTo = () => region.dataset.returnTo || "";
    const pasteEnabled = () => region.dataset.transferPasteEnabled === "true";

    const isMinimized = () => {
        try {
            return window.sessionStorage.getItem(minimizedStorageKey) === "true";
        } catch (error) {
            return false;
        }
    };

    const rememberMinimized = (minimized) => {
        try {
            window.sessionStorage.setItem(minimizedStorageKey, String(minimized));
        } catch (error) {
            // Minimize state is only a convenience.
        }
    };

    const appendHidden = (form, name, value) => {
        const input = document.createElement("input");
        input.type = "hidden";
        input.name = name;
        input.value = value;
        form.append(input);
    };

    const appendCsrf = (form) => {
        const csrf = csrfPair();
        if (csrf) {
            appendHidden(form, csrf.name, csrf.value);
        }
    };

    const appendReturnTo = (form) => {
        const value = returnTo();
        if (value) {
            appendHidden(form, "returnTo", value);
        }
    };

    const icon = (className) => {
        const element = document.createElement("i");
        element.className = className;
        element.setAttribute("aria-hidden", "true");
        return element;
    };

    const button = (className, iconClass, text) => {
        const element = document.createElement("button");
        element.className = className;
        element.type = "submit";
        if (iconClass) {
            element.append(icon(iconClass));
        }
        element.append(document.createTextNode(text));
        return element;
    };

    const transferForm = (operation, label, iconClass, className) => {
        const form = document.createElement("form");
        form.className = "row-form";
        form.method = "post";
        form.action = "/files/transfer/paste";
        form.dataset.transferAction = "transfer-paste";
        appendCsrf(form);
        appendHidden(form, "path", currentPath());
        appendHidden(form, "operation", operation);
        form.append(button(className, iconClass, label));
        return form;
    };

    const clearForm = () => {
        const form = document.createElement("form");
        form.className = "row-form";
        form.method = "post";
        form.action = "/files/transfer/clear";
        form.dataset.transferAction = "transfer-clear";
        appendCsrf(form);
        appendHidden(form, "path", currentPath());
        appendReturnTo(form);
        form.append(button("ghost transfer-buffer-clear", null, "Clear buffer"));
        return form;
    };

    const toggleButton = () => {
        const element = document.createElement("button");
        element.className = "ghost icon-button transfer-buffer-toggle js-only";
        element.type = "button";
        element.title = "Minimize transfer buffer";
        element.setAttribute("aria-label", "Minimize transfer buffer");
        element.setAttribute("aria-expanded", "true");
        element.append(icon("fas fa-chevron-down"));
        return element;
    };

    const removeForm = (item) => {
        const form = document.createElement("form");
        form.className = "row-form";
        form.method = "post";
        form.action = "/files/transfer/remove";
        form.dataset.transferAction = "transfer-remove";
        appendCsrf(form);
        appendHidden(form, "path", currentPath());
        appendHidden(form, "itemPath", item.path);
        appendReturnTo(form);

        const removeButton = document.createElement("button");
        removeButton.className = "transfer-buffer-remove";
        removeButton.type = "submit";
        removeButton.title = `Remove ${item.name} from transfer buffer`;
        removeButton.setAttribute("aria-label", `Remove ${item.name} from transfer buffer`);
        removeButton.append(icon("fas fa-xmark"));
        form.append(removeButton);
        return form;
    };

    const renderBuffer = (buffer) => {
        region.replaceChildren();
        if (!buffer?.active) {
            return;
        }

        const panel = document.createElement("section");
        panel.className = "transfer-buffer-panel";
        panel.setAttribute("aria-label", "Transfer buffer");

        const header = document.createElement("div");
        header.className = "transfer-buffer-header";
        const summary = document.createElement("div");
        summary.className = "transfer-buffer-summary";
        const summaryText = document.createElement("div");
        const title = document.createElement("strong");
        title.textContent = "Transfer buffer";
        const description = document.createElement("p");
        description.textContent = `${buffer.count} item(s) ready. Open a directory and choose what to do here.`;
        summaryText.append(title, description);
        summary.append(icon("fas fa-layer-group"), summaryText);
        const headerControls = document.createElement("div");
        headerControls.className = "transfer-buffer-header-controls";
        headerControls.append(clearForm(), toggleButton());
        header.append(summary, headerControls);

        const list = document.createElement("ul");
        list.className = "transfer-buffer-list";
        for (const item of buffer.items || []) {
            const entry = document.createElement("li");
            const name = document.createElement("span");
            name.title = item.path;
            name.textContent = item.name;
            entry.append(icon(item.iconClass), name, removeForm(item));
            list.append(entry);
        }

        const actions = pasteEnabled() ? document.createElement("div") : null;
        if (pasteEnabled()) {
            actions.className = "transfer-buffer-actions";
            actions.append(
                    transferForm("move", "Move here", "fas fa-file-import", "ghost"),
                    transferForm("copy", "Copy here", "fas fa-copy", "ghost")
            );
        }

        panel.append(header, list);
        if (actions) {
            panel.append(actions);
        }
        region.append(panel);
        bindTransferForms(panel);
        bindTransferToggles(panel);
        applyMinimized(panel, isMinimized());
    };

    const clearSelections = () => {
        if (!bulkForm) {
            return;
        }
        document.querySelectorAll('input[name="items"][form="bulkActionForm"]:checked').forEach((checkbox) => {
            checkbox.checked = false;
            checkbox.dispatchEvent(new Event("change", { bubbles: true }));
        });
    };

    const submitTarget = (form, submitter) => ({
        url: submitter?.getAttribute("formaction") || form.getAttribute("action") || form.action,
        method: submitter?.getAttribute("formmethod") || form.getAttribute("method") || form.method || "POST"
    });

    const setBusy = (form, submitter, busy) => {
        if (submitter) {
            submitter.disabled = busy;
        }
        form.querySelectorAll("button").forEach((control) => {
            control.disabled = busy;
        });
    };

    function bindTransferForms(root = document) {
        root.querySelectorAll("form[data-transfer-action]").forEach(bindTransferForm);
        root.querySelectorAll("button[data-transfer-action]").forEach((submitter) => {
            if (submitter.form) {
                bindTransferForm(submitter.form);
            }
        });
    }

    const applyMinimized = (panel, minimized) => {
        panel.classList.toggle("is-minimized", minimized);
        const toggle = panel.querySelector(".transfer-buffer-toggle");
        const toggleIcon = toggle?.querySelector("i");
        if (!toggle) {
            return;
        }

        toggle.setAttribute("aria-expanded", String(!minimized));
        toggle.title = minimized ? "Expand transfer buffer" : "Minimize transfer buffer";
        toggle.setAttribute("aria-label", toggle.title);
        toggleIcon?.classList.toggle("fa-chevron-down", !minimized);
        toggleIcon?.classList.toggle("fa-chevron-up", minimized);
    };

    function bindTransferToggles(root = document) {
        root.querySelectorAll(".transfer-buffer-toggle").forEach((toggle) => {
            if (toggle.dataset.transferToggleBound === "true") {
                return;
            }
            toggle.dataset.transferToggleBound = "true";
            toggle.addEventListener("click", () => {
                const panel = toggle.closest(".transfer-buffer-panel");
                if (!panel) {
                    return;
                }
                const minimized = !panel.classList.contains("is-minimized");
                rememberMinimized(minimized);
                applyMinimized(panel, minimized);
            });
        });
    }

    function bindTransferForm(form) {
        if (form.dataset.transferBound === "true") {
            return;
        }
        form.dataset.transferBound = "true";
        form.addEventListener("submit", async (event) => {
            const submitter = event.submitter;
            const action = submitter?.dataset?.transferAction || form.dataset.transferAction;
            if (!action) {
                return;
            }

            event.preventDefault();
            const target = submitTarget(form, submitter);
            const formData = new FormData(form);
            if (submitter?.name) {
                formData.append(submitter.name, submitter.value);
            }

            setBusy(form, submitter, true);
            try {
                const body = await submitJsonForm(form, formData, target.url, target.method);
                if (body.redirectUrl && navigateWithNotification(body)) {
                    return;
                }
                showNotification(body.notification);
                if (Object.prototype.hasOwnProperty.call(body, "transferBuffer")) {
                    renderBuffer(body.transferBuffer);
                }
                if (body.task) {
                    window.EnderVaultServerTasks?.track(body.task, {
                        refreshUrl: window.location.href
                    });
                }
                if (action === "transfer-buffer-add") {
                    clearSelections();
                }
            } catch (error) {
                showToast("error", error.message || "The transfer action failed.");
            } finally {
                setBusy(form, submitter, false);
            }
        });
    }

    bindTransferForms();
    bindTransferToggles();
    document.querySelectorAll(".transfer-buffer-panel").forEach((panel) => {
        applyMinimized(panel, isMinimized());
    });
});
