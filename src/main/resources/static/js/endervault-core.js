(function () {
    const jsonHeaders = {
        "Accept": "application/json",
        "X-Requested-With": "fetch"
    };

    const parseJsonBody = async (response) => {
        const contentType = response.headers.get("content-type") || "";
        return contentType.includes("application/json") ? response.json() : null;
    };

    const requestJson = async (url, {
        method = "GET",
        body = null,
        headers = {},
        signal
    } = {}) => {
        const response = await fetch(url, {
            method,
            body,
            headers: { ...jsonHeaders, ...headers },
            credentials: "same-origin",
            signal
        });
        const payload = await parseJsonBody(response);
        const redirectedToLogin = response.redirected
            && new URL(response.url, window.location.href).pathname === "/login";
        const sessionExpired = redirectedToLogin
            || response.status === 401
            || (response.status === 403 && !payload);
        if (!response.ok || !payload || payload.ok === false) {
            const error = new Error(
                sessionExpired
                    ? "Your session expired. Log in again before continuing."
                    : payload?.notification?.message || "The action failed."
            );
            error.status = response.status;
            error.payload = payload;
            error.sessionExpired = sessionExpired;
            throw error;
        }
        return payload;
    };

    const formAction = (form) => form.getAttribute("action") || window.location.href;
    const formMethod = (form) => form.getAttribute("method") || "POST";

    const submitJsonForm = (
            form,
            formData = new FormData(form),
            action = formAction(form),
            method = formMethod(form)
    ) =>
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
            actionValue: notification.actionValue || "",
            actionHref: notification.actionHref || ""
        });
    };

    const showToast = (type, message, options = {}) => {
        showNotification({ type, message, ...options });
    };

    const copyText = async (value) => {
        if (!value) {
            return false;
        }

        if (navigator.clipboard && window.isSecureContext) {
            await navigator.clipboard.writeText(value);
            return true;
        }

        const textarea = document.createElement("textarea");
        textarea.value = value;
        textarea.setAttribute("readonly", "");
        textarea.style.position = "fixed";
        textarea.style.left = "-9999px";
        textarea.style.top = "0";
        document.body.append(textarea);
        textarea.select();
        let copied = false;
        try {
            copied = document.execCommand("copy");
        } finally {
            textarea.remove();
        }
        return copied;
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

    const formDataWithConflictPolicy = (source, policy) => {
        if (!(source instanceof FormData)) {
            return source;
        }
        const next = cloneFormData(source);
        next.set("conflictPolicy", policy);
        return next;
    };

    // DialogHost installs the shared renderer. Never confirm an action before it is ready.
    const dialogUnavailable = () => {
        const message = "Dialog controls are not ready. Reload the page and try again.";
        showToast("error", message);
        return new Error(message);
    };
    const askTextInput = async () => { dialogUnavailable(); return null; };
    const askConfirmation = async () => { dialogUnavailable(); return false; };
    const askFileConflictPolicy = async () => { throw dialogUnavailable(); };

    const requestJsonResolvingConflicts = async (
            url,
            { method = "GET", body = null, headers = {} } = {}
    ) => {
        let currentBody = body;
        while (true) {
            try {
                return await requestJson(url, { method, body: currentBody, headers });
            } catch (error) {
                if (error.status !== 409 || !error.payload?.conflict) {
                    throw error;
                }
                const policy = await window.EnderVault.askFileConflictPolicy(error.payload.conflict);
                currentBody = formDataWithConflictPolicy(body, policy);
            }
        }
    };

    const submitJsonFormResolvingConflicts = (
            form,
            formData = new FormData(form),
            action = formAction(form),
            method = formMethod(form)
    ) => requestJsonResolvingConflicts(action, {
        method: method.toUpperCase(),
        body: formData
    });

    const rememberNotification = (notification) => {
        if (!notification) {
            return;
        }

        try {
            window.sessionStorage.setItem("endervault.pendingToast", JSON.stringify({
                type: notification.type,
                message: notification.message,
                actionLabel: notification.actionLabel || "Copy",
                actionValue: notification.actionValue || "",
                actionHref: notification.actionHref || ""
            }));
        } catch (error) {
            // The redirect can still happen; the toast is progressive enhancement.
        }
    };

    const navigate = (url, notification = null) => {
        if (!url) {
            return false;
        }

        const event = new CustomEvent("endervault:navigate", {
            cancelable: true,
            detail: { url }
        });
        if (document.dispatchEvent(event)) {
            rememberNotification(notification);
            window.location.assign(url);
        } else if (notification) {
            showNotification(notification);
        }
        return true;
    };

    const navigateWithNotification = (body) => {
        if (!body?.redirectUrl) {
            return false;
        }

        return navigate(body.redirectUrl, body.notification);
    };

    const contextMenus = (() => {
        let activeCloser = null;

        return {
            open(closer) {
                if (activeCloser && activeCloser !== closer) {
                    const closePrevious = activeCloser;
                    activeCloser = null;
                    closePrevious();
                }
                activeCloser = closer;
            },
            close() {
                if (!activeCloser) {
                    return;
                }
                const closeActive = activeCloser;
                activeCloser = null;
                closeActive();
            },
            clear(closer) {
                if (activeCloser === closer) {
                    activeCloser = null;
                }
            }
        };
    })();


    window.EnderVault = {
        requestJson,
        submitJsonForm,
        requestJsonResolvingConflicts,
        submitJsonFormResolvingConflicts,
        askFileConflictPolicy,
        askTextInput,
        askConfirmation,
        showNotification,
        showToast,
        copyText,
        csrfInput,
        csrfPair,
        cloneFormData,
        rememberNotification,
        navigateWithNotification,
        navigate,
        contextMenus
    };
})();
