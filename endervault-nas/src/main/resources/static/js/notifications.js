document.addEventListener("DOMContentLoaded", () => {
    const toastRegion = document.getElementById("toastRegion");
    const autoDismissMs = 6500;

    if (!toastRegion) {
        return;
    }

    const dismiss = (toast) => {
        if (!toast || toast.classList.contains("is-dismissing")) {
            return;
        }
        toast.classList.add("is-dismissing");
        window.setTimeout(() => toast.remove(), 180);
    };

    const copyValue = async (button) => {
        const value = button.dataset.copyValue;
        if (!value) {
            return;
        }

        try {
            const copied = await window.EnderVault.copyText(value);
            if (!copied) {
                throw new Error("Copy failed.");
            }

            const previousTitle = button.title;
            button.title = "Copied";
            button.setAttribute("aria-label", "Copied");
            window.setTimeout(() => {
                button.title = previousTitle || "Copy";
                button.setAttribute("aria-label", previousTitle || "Copy");
            }, 1400);
        } catch (error) {
            button.title = "Copy failed";
            button.setAttribute("aria-label", "Copy failed");
        }
    };

    const bindToast = (toast) => {
        toast.querySelector(".toast-close")?.addEventListener("click", () => dismiss(toast));
        toast.querySelector(".toast-copy")?.addEventListener("click", (event) => copyValue(event.currentTarget));

        if (toast.dataset.toastPersistent === "true") {
            return;
        }

        window.setTimeout(() => dismiss(toast), autoDismissMs);
    };

    const show = ({ type = "info", message, actionLabel = "Copy", actionValue = "" }) => {
        if (!message) {
            return;
        }

        const toast = document.createElement("article");
        toast.className = `toast toast-${type}`;
        toast.dataset.toast = "true";
        if (actionValue) {
            toast.dataset.toastPersistent = "true";
        }

        const icon = document.createElement("i");
        icon.className = `toast-icon ${iconClass(type)}`;
        icon.setAttribute("aria-hidden", "true");
        toast.append(icon);

        const content = document.createElement("div");
        content.className = "toast-content";
        const text = document.createElement("p");
        text.className = "toast-message";
        text.textContent = message;
        content.append(text);

        if (actionValue) {
            content.append(actionControl(actionLabel, actionValue));
        }

        toast.append(content);
        toast.append(closeButton());
        toastRegion.append(toast);
        bindToast(toast);
    };

    const iconClass = (type) => {
        switch (type) {
            case "success":
                return "fas fa-circle-check";
            case "warning":
                return "fas fa-triangle-exclamation";
            case "error":
                return "fas fa-circle-exclamation";
            default:
                return "fas fa-circle-info";
        }
    };

    const actionControl = (label, value) => {
        const wrapper = document.createElement("div");
        wrapper.className = "toast-action";

        const input = document.createElement("input");
        input.readOnly = true;
        input.value = value;

        const button = document.createElement("button");
        button.className = "ghost icon-button toast-copy";
        button.type = "button";
        button.title = label;
        button.setAttribute("aria-label", label);
        button.dataset.copyValue = value;
        button.innerHTML = '<i class="fas fa-copy" aria-hidden="true"></i>';

        wrapper.append(input, button);
        return wrapper;
    };

    const closeButton = () => {
        const button = document.createElement("button");
        button.className = "ghost icon-button toast-close js-only";
        button.type = "button";
        button.title = "Dismiss";
        button.setAttribute("aria-label", "Dismiss");
        button.innerHTML = '<i class="fas fa-xmark" aria-hidden="true"></i>';
        return button;
    };

    window.EnderVaultToasts = { show, dismiss };
    toastRegion.querySelectorAll("[data-toast]").forEach(bindToast);

    try {
        const pendingToast = window.sessionStorage.getItem("endervault.pendingToast");
        if (pendingToast) {
            window.sessionStorage.removeItem("endervault.pendingToast");
            show(JSON.parse(pendingToast));
        }
    } catch (error) {
        // Ignore unavailable storage; the action itself already succeeded.
    }
});
