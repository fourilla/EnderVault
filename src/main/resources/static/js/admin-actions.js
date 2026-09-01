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

    const conflictAwareActions = new Set(["detail-rename", "detail-move", "detail-hidden"]);

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

    const handleSuccess = (form, body, action = ajaxAction(form)) => {
        if ([
            "detail-rename",
            "detail-move",
            "detail-hidden",
            "detail-delete",
            "recent-clear",
            "view-preferences-reset"
        ].includes(action)
                && navigateWithNotification(body)) {
            return;
        }

        showNotification(body.notification);

        switch (action) {
            case "bookmark-bulk-add":
                if (body.task) {
                    window.EnderVaultServerTasks?.track(body.task);
                }
                form.reset();
                form.closest("details")?.removeAttribute("open");
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
            let successBody = null;
            try {
                const submit = conflictAware ? submitJsonFormResolvingConflicts : submitJsonForm;
                const body = await submit(form, formData, target.url, target.method);
                handleSuccess(form, body, action);
                successBody = body;
            } catch (error) {
                window.EnderVault.showToast("error", error.message || "The action failed.");
            } finally {
                setBusy(form, false);
                if (successBody) {
                    form.dispatchEvent(new CustomEvent("endervault:ajax-success", {
                        bubbles: true,
                        detail: { action, body: successBody }
                    }));
                }
            }
        });
    }

    document.querySelectorAll("form[data-ajax-action]").forEach(bindAjaxForm);
    bindCopyButtons();
    window.EnderVaultActions = { submitJsonForm, showNotification };
});
