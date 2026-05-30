document.addEventListener("DOMContentLoaded", () => {
    const submitJsonForm = async (form) => {
        const response = await fetch(form.action, {
            method: form.method || "POST",
            body: new FormData(form),
            headers: {
                "Accept": "application/json",
                "X-Requested-With": "fetch"
            },
            credentials: "same-origin"
        });

        const contentType = response.headers.get("content-type") || "";
        const body = contentType.includes("application/json") ? await response.json() : null;
        if (!response.ok || !body || body.ok === false) {
            const message = body?.notification?.message || "The action failed.";
            throw new Error(message);
        }
        return body;
    };

    const showNotification = (notification) => {
        if (!notification || !window.EnderVaultToasts) {
            return;
        }
        window.EnderVaultToasts.show({
            type: notification.type,
            message: notification.message,
            actionLabel: notification.actionLabel || "Copy",
            actionValue: notification.actionValue || ""
        });
    };

    const setBusy = (form, busy) => {
        form.querySelectorAll("button, input, select").forEach((control) => {
            if (control.type !== "hidden") {
                control.disabled = busy;
            }
        });
    };

    const rememberNotification = (notification) => {
        if (!notification) {
            return;
        }

        try {
            window.sessionStorage.setItem("endervault.pendingToast", JSON.stringify({
                type: notification.type,
                message: notification.message,
                actionLabel: notification.actionLabel || "Copy",
                actionValue: notification.actionValue || ""
            }));
        } catch (error) {
            // The redirect can still happen; the toast is progressive enhancement.
        }
    };

    const navigateWithNotification = (body) => {
        if (!body.redirectUrl) {
            return false;
        }

        rememberNotification(body.notification);
        window.location.assign(body.redirectUrl);
        return true;
    };

    const csrfInput = () => document.querySelector('input[name="_csrf"]')?.cloneNode();

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

    const handleSuccess = (form, body) => {
        if (["detail-rename", "detail-move", "detail-delete"].includes(form.dataset.ajaxAction)
                && navigateWithNotification(body)) {
            return;
        }

        showNotification(body.notification);

        switch (form.dataset.ajaxAction) {
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
            setBusy(form, true);
            try {
                const body = await submitJsonForm(form);
                handleSuccess(form, body);
            } catch (error) {
                if (window.EnderVaultToasts) {
                    window.EnderVaultToasts.show({
                        type: "error",
                        message: error.message || "The action failed."
                    });
                }
            } finally {
                setBusy(form, false);
            }
        });
    }

    document.querySelectorAll("form[data-ajax-action]").forEach(bindAjaxForm);
    window.EnderVaultActions = { submitJsonForm, showNotification };
});
