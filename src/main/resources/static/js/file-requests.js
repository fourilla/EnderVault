document.addEventListener("DOMContentLoaded", () => {
    const { submitJsonForm, showNotification, rememberNotification } = window.EnderVault;

    const setBusy = (form, busy) => {
        form.querySelectorAll("button, input, select, textarea").forEach((control) => {
            control.disabled = busy;
        });
    };

    const submit = async (form) => {
        setBusy(form, true);
        try {
            const payload = await submitJsonForm(form);
            if (payload.redirectUrl) {
                rememberNotification(payload.notification);
                window.location.assign(payload.redirectUrl);
                return;
            }
            showNotification(payload.notification);
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
