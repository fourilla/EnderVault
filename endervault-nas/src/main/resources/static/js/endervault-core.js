(function () {
    const jsonHeaders = {
        "Accept": "application/json",
        "X-Requested-With": "fetch"
    };

    const parseJsonBody = async (response) => {
        const contentType = response.headers.get("content-type") || "";
        return contentType.includes("application/json") ? response.json() : null;
    };

    const requestJson = async (url, { method = "GET", body = null, headers = {} } = {}) => {
        const response = await fetch(url, {
            method,
            body,
            headers: { ...jsonHeaders, ...headers },
            credentials: "same-origin"
        });
        const payload = await parseJsonBody(response);
        if (!response.ok || !payload || payload.ok === false) {
            throw new Error(payload?.notification?.message || "The action failed.");
        }
        return payload;
    };

    const submitJsonForm = (form, formData = new FormData(form), action = form.action, method = form.method || "POST") =>
        requestJson(action, {
            method: method.toUpperCase(),
            body: formData
        });

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

    const showToast = (type, message) => {
        showNotification({ type, message });
    };

    const csrfInput = (root = document) =>
        root?.querySelector('input[name="_csrf"]') || document.querySelector('input[name="_csrf"]');

    const csrfPair = (root = document) => {
        const input = csrfInput(root);
        return input ? { name: input.name, value: input.value } : null;
    };

    const cloneFormData = (source) => {
        const next = new FormData();
        for (const [key, value] of source.entries()) {
            next.append(key, value);
        }
        return next;
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
        if (!body?.redirectUrl) {
            return false;
        }

        rememberNotification(body.notification);
        window.location.assign(body.redirectUrl);
        return true;
    };

    window.EnderVault = {
        requestJson,
        submitJsonForm,
        showNotification,
        showToast,
        csrfInput,
        csrfPair,
        cloneFormData,
        rememberNotification,
        navigateWithNotification
    };
})();
