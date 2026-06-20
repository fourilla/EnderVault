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
            const error = new Error(payload?.notification?.message || "The action failed.");
            error.status = response.status;
            error.payload = payload;
            throw error;
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

    const ensureFileConflictDialog = () => {
        let dialog = document.getElementById("fileConflictDialog");
        if (dialog) {
            return dialog;
        }

        dialog = document.createElement("dialog");
        dialog.id = "fileConflictDialog";
        dialog.className = "upload-conflict-dialog";
        dialog.innerHTML = `
            <form method="dialog" class="upload-conflict-card">
                <header class="upload-conflict-header">
                    <div>
                        <h2>File name conflict</h2>
                        <p data-conflict-message></p>
                    </div>
                    <button class="ghost icon-button action-icon" value="default" type="submit" title="Use default policy" aria-label="Use default policy">
                        <i class="fas fa-xmark" aria-hidden="true"></i>
                    </button>
                </header>
                <div class="upload-conflict-actions">
                    <button class="ghost icon-text-button" value="rename" type="submit">
                        <span>Rename and Continue</span>
                    </button>
                    <button class="danger icon-text-button" value="overwrite" type="submit">
                        <span>Overwrite</span>
                    </button>
                    <button class="ghost icon-text-button" value="cancel" type="submit">
                        <span>Cancel</span>
                    </button>
                </div>
            </form>
        `;
        document.body.append(dialog);
        return dialog;
    };

    const askFileConflictPolicy = (conflict) => new Promise((resolve) => {
        const dialog = ensureFileConflictDialog();
        const message = dialog.querySelector("[data-conflict-message]");
        const defaultPolicy = conflict?.defaultPolicy || "cancel";
        message.textContent = conflict?.message
                ? `${conflict.message} Closing uses the default policy: ${defaultPolicy}.`
                : `Choose how to handle this conflict. Closing uses the default policy: ${defaultPolicy}.`;
        dialog.returnValue = "default";

        const onClose = () => {
            dialog.removeEventListener("close", onClose);
            resolve(dialog.returnValue || "default");
        };
        dialog.addEventListener("close", onClose);

        if (typeof dialog.showModal === "function") {
            dialog.showModal();
            return;
        }
        resolve("default");
    });

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
                const policy = await askFileConflictPolicy(error.payload.conflict);
                currentBody = formDataWithConflictPolicy(body, policy);
            }
        }
    };

    const submitJsonFormResolvingConflicts = (
            form,
            formData = new FormData(form),
            action = form.action,
            method = form.method || "POST"
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

    const navigateWithNotification = (body) => {
        if (!body?.redirectUrl) {
            return false;
        }

        rememberNotification(body.notification);
        window.location.assign(body.redirectUrl);
        return true;
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

    const closeDetails = (details) => {
        if (!details.open) {
            return;
        }

        details.open = false;
    };

    const enhanceDismissibleDetails = () => {
        const menus = Array.from(document.querySelectorAll("details.settings-menu"));
        if (menus.length === 0) {
            return;
        }

        menus.forEach((menu) => {
            menu.addEventListener("toggle", () => {
                if (!menu.open) {
                    return;
                }

                menus.forEach((otherMenu) => {
                    if (otherMenu !== menu) {
                        closeDetails(otherMenu);
                    }
                });
            });
        });

        document.addEventListener("click", (event) => {
            menus.forEach((menu) => {
                if (menu.open && !menu.contains(event.target)) {
                    closeDetails(menu);
                }
            });
        });

        document.addEventListener("keydown", (event) => {
            if (event.key !== "Escape") {
                return;
            }

            menus.forEach((menu) => closeDetails(menu));
        });
    };

    document.addEventListener("DOMContentLoaded", enhanceDismissibleDetails);

    window.EnderVault = {
        requestJson,
        submitJsonForm,
        requestJsonResolvingConflicts,
        submitJsonFormResolvingConflicts,
        showNotification,
        showToast,
        copyText,
        csrfInput,
        csrfPair,
        cloneFormData,
        rememberNotification,
        navigateWithNotification,
        contextMenus
    };
})();
