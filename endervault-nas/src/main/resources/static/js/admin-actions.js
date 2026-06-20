document.addEventListener("DOMContentLoaded", () => {
    const {
        submitJsonForm,
        submitJsonFormResolvingConflicts,
        showNotification,
        showToast,
        navigateWithNotification,
        copyText
    } = window.EnderVault;

    const setBusy = (form, busy) => {
        form.querySelectorAll("button, input, select, textarea").forEach((control) => {
            if (control.tagName === "BUTTON") {
                control.disabled = busy;
                return;
            }
            if (control.type !== "hidden" && "readOnly" in control) {
                control.readOnly = busy;
            }
        });
    };

    const ajaxAction = (form, submitter = null) =>
        submitter?.dataset?.ajaxAction || form.dataset.ajaxAction || "";

    const conflictAwareActions = new Set(["detail-rename", "detail-move"]);

    const submitAction = (form, submitter = null) => ({
        url: submitter?.getAttribute("formaction") || form.getAttribute("action") || form.action,
        method: submitter?.getAttribute("formmethod") || form.getAttribute("method") || form.method || "POST"
    });

    const formDataForSubmitter = (form, submitter = null) => {
        if (!submitter) {
            return new FormData(form);
        }
        try {
            return new FormData(form, submitter);
        } catch (error) {
            const formData = new FormData(form);
            if (submitter.name) {
                formData.append(submitter.name, submitter.value);
            }
            return formData;
        }
    };

    const csrfInput = () => window.EnderVault.csrfInput()?.cloneNode();

    const bindCopyButtons = (root = document) => {
        root.querySelectorAll("[data-copy-value]").forEach((button) => {
            if (button.dataset.copyBound === "true") {
                return;
            }

            button.dataset.copyBound = "true";
            button.addEventListener("click", async () => {
                try {
                    const copied = await copyText(button.dataset.copyValue);
                    if (!copied) {
                        throw new Error("Copy failed.");
                    }
                    showToast("success", button.dataset.copySuccess || "Copied.");
                } catch (error) {
                    showToast("error", "Copy failed.");
                }
            });
        });
    };

    const appendShareRow = (form, shareLink) => {
        const tableBody = document.querySelector(form.dataset.shareTable);
        if (!tableBody || !shareLink) {
            return;
        }

        tableBody.querySelector(".empty-row")?.remove();
        const row = document.createElement("tr");
        row.dataset.shareToken = shareLink.token;
        row.dataset.shareStatus = shareLink.statusClass;

        const csrf = csrfInput();
        const path = form.querySelector('input[name="path"]')?.value || "";

        row.innerHTML = `
            <td><input readonly value=""></td>
            <td></td>
            <td></td>
            <td><span class="status-badge"></span></td>
            <td>
                <div class="table-actions">
                    <button class="ghost icon-button action-icon js-only" type="button"
                            title="Copy link" aria-label="Copy link" data-copy-value="" data-copy-success="Share link copied.">
                        <i class="fas fa-link" aria-hidden="true"></i>
                    </button>
                    <button class="ghost icon-button action-icon js-only" type="button"
                            title="Copy direct download link" aria-label="Copy direct download link"
                            data-direct-download-copy data-copy-value="" data-copy-success="Direct download link copied.">
                        <i class="fas fa-file-arrow-down" aria-hidden="true"></i>
                    </button>
                    <form class="row-form" method="post" action="/files/detail/shares/revoke" data-ajax-action="share-revoke">
                        <input type="hidden" name="path" value="">
                        <input type="hidden" name="token" value="">
                        <button class="danger icon-button action-icon" type="submit" title="Revoke" aria-label="Revoke">
                            <i class="fas fa-link-slash" aria-hidden="true"></i>
                        </button>
                    </form>
                    <form class="row-form" method="post" action="/files/detail/shares/delete" data-ajax-action="share-delete">
                        <input type="hidden" name="path" value="">
                        <input type="hidden" name="token" value="">
                        <button class="ghost icon-button action-icon" type="submit" title="Delete" aria-label="Delete">
                            <i class="fas fa-trash-can" aria-hidden="true"></i>
                        </button>
                    </form>
                </div>
            </td>
        `;

        row.querySelector("td input[readonly]").value = shareLink.url;
        row.querySelector('button[aria-label="Copy link"]').dataset.copyValue = shareLink.url;
        const directDownloadButton = row.querySelector("[data-direct-download-copy]");
        if (shareLink.directDownloadUrl) {
            directDownloadButton.dataset.copyValue = shareLink.directDownloadUrl;
        } else {
            directDownloadButton.remove();
        }
        row.children[1].textContent = shareLink.createdLabel;
        row.children[2].textContent = shareLink.expiresLabel;
        const status = row.querySelector(".status-badge");
        status.className = `status-badge ${shareLink.statusClass}`;
        status.textContent = shareLink.statusLabel;
        row.querySelectorAll('input[name="path"]').forEach((input) => {
            input.value = path;
        });
        row.querySelectorAll('input[name="token"]').forEach((input) => {
            input.value = shareLink.token;
        });
        row.querySelectorAll("form").forEach((rowForm) => {
            if (csrf) {
                rowForm.prepend(csrf.cloneNode());
            }
            bindAjaxForm(rowForm);
        });
        bindCopyButtons(row);

        tableBody.prepend(row);
    };

    const markShareRevoked = (form) => {
        const row = form.closest("tr");
        if (!row) {
            return;
        }
        row.dataset.shareStatus = "revoked";
        const status = row.querySelector(".status-badge");
        if (status) {
            status.className = "status-badge revoked";
            status.textContent = "Revoked";
        }
        form.remove();
    };

    const removeShareRow = (form) => {
        form.closest("tr")?.remove();
    };

    const removeExpiredShareRows = () => {
        document.querySelectorAll('tr[data-share-status="expired"]').forEach((row) => row.remove());
    };

    const settingToggleControllers = () =>
        Array.from(document.querySelectorAll("input[type='checkbox'][data-toggle-target]"));

    const syncSwitchStateLabels = () => {
        document.querySelectorAll("input[type='checkbox'][data-state-label]").forEach((controller) => {
            const label = document.querySelector(controller.dataset.stateLabel);
            if (!label) {
                return;
            }

            const active = controller.checked;
            const onClass = controller.dataset.onClass || "active";
            const offClass = controller.dataset.offClass || "expired";
            label.textContent = active
                    ? (controller.dataset.onLabel || "Enabled")
                    : (controller.dataset.offLabel || "Disabled");
            label.className = `status-badge ${active ? onClass : offClass}`;
        });
    };

    const syncSettingDependencies = () => {
        const controllers = settingToggleControllers();
        const controlled = new Set();

        controllers.forEach((controller) => {
            document.querySelectorAll(controller.dataset.toggleTarget).forEach((element) => {
                controlled.add(element);
            });
        });

        controlled.forEach((element) => {
            const locked = controllers.some((controller) =>
                !controller.checked && element.matches(controller.dataset.toggleTarget));
            element.classList.toggle("settings-dependent-locked", locked);
            element.setAttribute("aria-disabled", String(locked));
            element.querySelectorAll("input, select, textarea, button, a").forEach((control) => {
                if (control === element) {
                    return;
                }
                if ("readOnly" in control) {
                    control.readOnly = locked;
                }
                if (locked) {
                    if (!control.dataset.previousTabIndex && control.hasAttribute("tabindex")) {
                        control.dataset.previousTabIndex = control.getAttribute("tabindex");
                    }
                    control.setAttribute("tabindex", "-1");
                } else if (control.dataset.previousTabIndex) {
                    control.setAttribute("tabindex", control.dataset.previousTabIndex);
                    delete control.dataset.previousTabIndex;
                } else {
                    control.removeAttribute("tabindex");
                }
            });
        });
        syncSwitchStateLabels();
    };

    const enhanceSettingDependencies = () => {
        settingToggleControllers().forEach((controller) => {
            if (controller.dataset.settingDependencyBound === "true") {
                return;
            }
            controller.dataset.settingDependencyBound = "true";
            controller.addEventListener("change", syncSettingDependencies);
        });
        document.querySelectorAll("input[type='checkbox'][data-state-label]").forEach((controller) => {
            if (controller.dataset.switchStateBound === "true") {
                return;
            }
            controller.dataset.switchStateBound = "true";
            controller.addEventListener("change", syncSwitchStateLabels);
        });
        syncSettingDependencies();
    };

    const handleSuccess = (form, body, action = ajaxAction(form)) => {
        if (["detail-rename", "detail-move", "detail-delete"].includes(action)
                && navigateWithNotification(body)) {
            return;
        }

        showNotification(body.notification);

        switch (action) {
            case "share-create":
                appendShareRow(form, body.shareLink);
                form.reset();
                break;
            case "share-revoke":
                markShareRevoked(form);
                break;
            case "share-delete":
                removeShareRow(form);
                break;
            case "share-delete-expired":
                removeExpiredShareRows();
                break;
            case "metadata-scan":
                window.EnderVaultServerTasks?.track(body.task);
                break;
            case "metadata-repair":
                window.EnderVaultMetadata?.handleRepair(body, form);
                break;
            default:
                break;
        }
    };

    function bindAjaxForm(form) {
        if (form.dataset.ajaxBound === "true") {
            return;
        }
        form.dataset.ajaxBound = "true";

        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            const submitter = event.submitter;
            const action = ajaxAction(form, submitter);
            const target = submitAction(form, submitter);
            const formData = formDataForSubmitter(form, submitter);
            const conflictAware = conflictAwareActions.has(action);
            if (conflictAware) {
                formData.set("conflictPolicy", "ask");
            }
            setBusy(form, true);
            try {
                const submit = conflictAware ? submitJsonFormResolvingConflicts : submitJsonForm;
                const body = await submit(form, formData, target.url, target.method);
                handleSuccess(form, body, action);
            } catch (error) {
                window.EnderVault.showToast("error", error.message || "The action failed.");
            } finally {
                setBusy(form, false);
                syncSettingDependencies();
                window.EnderVaultMetadata?.syncSelection?.();
            }
        });
    }

    document.querySelectorAll("form[data-ajax-action]").forEach(bindAjaxForm);
    bindCopyButtons();
    enhanceSettingDependencies();
    window.EnderVaultActions = { submitJsonForm, showNotification };
});
