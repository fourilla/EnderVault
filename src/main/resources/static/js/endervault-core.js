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

    const ensureTextInputDialog = () => {
        let dialog = document.getElementById("textInputDialog");
        if (dialog) {
            return dialog;
        }

        dialog = document.createElement("dialog");
        dialog.id = "textInputDialog";
        dialog.className = "text-input-dialog";
        dialog.innerHTML = `
            <form method="dialog" class="text-input-card" novalidate>
                <header class="text-input-header">
                    <div>
                        <h2 data-text-input-title>Input</h2>
                        <p data-text-input-message hidden></p>
                    </div>
                    <button class="ghost icon-button action-icon" value="cancel" type="submit" title="Cancel" aria-label="Cancel">
                        <i class="fas fa-xmark" aria-hidden="true"></i>
                    </button>
                </header>
                <label class="text-input-field">
                    <span data-text-input-label>Name</span>
                    <input data-text-input-control type="text" autocomplete="off">
                </label>
                <p class="text-input-error" data-text-input-error hidden>Name is required.</p>
                <div class="text-input-actions">
                    <button class="ghost icon-text-button" value="cancel" type="submit">Cancel</button>
                    <button class="primary icon-text-button" value="confirm" type="submit" data-text-input-confirm>Create</button>
                </div>
            </form>
        `;
        document.body.append(dialog);
        return dialog;
    };

    const askTextInput = ({
        title = "Input",
        message = "",
        label = "Name",
        placeholder = "",
        initialValue = "",
        confirmLabel = "Create"
    } = {}) => new Promise((resolve) => {
        const dialog = ensureTextInputDialog();
        const form = dialog.querySelector("form");
        const titleElement = dialog.querySelector("[data-text-input-title]");
        const messageElement = dialog.querySelector("[data-text-input-message]");
        const labelElement = dialog.querySelector("[data-text-input-label]");
        const input = dialog.querySelector("[data-text-input-control]");
        const error = dialog.querySelector("[data-text-input-error]");
        const confirm = dialog.querySelector("[data-text-input-confirm]");

        if (typeof dialog.showModal !== "function") {
            const fallback = window.prompt(title, initialValue || "");
            resolve(fallback == null ? null : fallback.trim());
            return;
        }

        titleElement.textContent = title;
        labelElement.textContent = label;
        confirm.textContent = confirmLabel;
        input.value = initialValue || "";
        input.placeholder = placeholder || "";
        error.hidden = true;
        if (message) {
            messageElement.textContent = message;
            messageElement.hidden = false;
        } else {
            messageElement.textContent = "";
            messageElement.hidden = true;
        }

        const onSubmit = (event) => {
            if (event.submitter?.value !== "confirm") {
                return;
            }
            if (input.value.trim()) {
                return;
            }
            event.preventDefault();
            error.hidden = false;
            input.focus();
        };

        const onInputKeydown = (event) => {
            if (event.key !== "Enter" || event.isComposing || event.keyCode === 229) {
                return;
            }
            event.preventDefault();
            if (!input.value.trim()) {
                error.hidden = false;
                input.focus();
                return;
            }
            dialog.close("confirm");
        };

        const onClose = () => {
            form.removeEventListener("submit", onSubmit);
            input.removeEventListener("keydown", onInputKeydown);
            dialog.removeEventListener("close", onClose);
            const value = dialog.returnValue === "confirm" ? input.value.trim() : null;
            form.reset();
            resolve(value);
        };

        form.addEventListener("submit", onSubmit);
        input.addEventListener("keydown", onInputKeydown);
        dialog.addEventListener("close", onClose);
        dialog.returnValue = "cancel";
        dialog.showModal();
        input.focus();
        input.select();
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
        askFileConflictPolicy,
        askTextInput,
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
