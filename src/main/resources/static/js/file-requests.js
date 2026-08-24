document.addEventListener("DOMContentLoaded", () => {
    const { submitJsonForm, showNotification, rememberNotification } = window.EnderVault;

    const setBusy = (form, busy) => {
        form.querySelectorAll("button, input, select, textarea").forEach((control) => {
            if (busy) {
                control.dataset.fileRequestWasDisabled = String(control.disabled);
                control.disabled = true;
                return;
            }
            control.disabled = control.dataset.fileRequestWasDisabled === "true";
            delete control.dataset.fileRequestWasDisabled;
        });
    };

    const markRevoked = (form) => {
        const scope = form.closest("[data-file-request-scope]") || document;
        scope.querySelectorAll("[data-file-request-status]").forEach((badge) => {
            badge.classList.remove("active", "warning", "expired", "revoked");
            badge.classList.add("revoked");
            badge.textContent = "Revoked";
        });
        scope.querySelectorAll("[data-file-request-action='revoke']").forEach((revokeForm) => {
            revokeForm.hidden = true;
        });
        scope.querySelectorAll("[data-file-request-action='delete']").forEach((deleteForm) => {
            deleteForm.hidden = false;
        });
        scope.querySelectorAll("[data-file-request-delete-panel]").forEach((panel) => {
            panel.hidden = false;
        });
    };

    const removeListRow = (form) => {
        form.closest("[data-file-request-row]")?.remove();
        if (document.querySelector("[data-file-request-row]")) {
            return;
        }
        const emptyRow = document.querySelector("[data-file-request-empty-row]");
        if (emptyRow) {
            emptyRow.hidden = false;
        }
        document.querySelectorAll("[data-file-request-list-action]").forEach((action) => {
            action.hidden = true;
        });
    };

    const handleSuccess = (form, payload) => {
        const action = form.dataset.fileRequestAction;
        if (payload.redirectUrl) {
            rememberNotification(payload.notification);
            window.location.assign(payload.redirectUrl);
            return;
        }
        if (action === "revoke") {
            markRevoked(form);
        } else if (action === "delete") {
            if (form.dataset.successUrl) {
                rememberNotification(payload.notification);
                window.location.assign(form.dataset.successUrl);
                return;
            }
            removeListRow(form);
        }
        showNotification(payload.notification);
    };

    const submit = async (form) => {
        const formData = new FormData(form);
        setBusy(form, true);
        try {
            const payload = await submitJsonForm(form, formData);
            handleSuccess(form, payload);
        } catch (error) {
            showNotification(error.payload?.notification || {
                type: "error",
                message: error.message || "The file request action failed."
            });
        } finally {
            setBusy(form, false);
        }
    };

    document.querySelectorAll("[data-file-request-form]").forEach((form) => {
        form.addEventListener("submit", (event) => {
            event.preventDefault();
            const confirmation = form.dataset.confirm;
            if (confirmation && !window.confirm(confirmation)) {
                return;
            }
            submit(form);
        });
    });
});
