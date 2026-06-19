document.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll("[data-password-toggle]").forEach((button) => {
        if (button.dataset.passwordToggleBound === "true") {
            return;
        }

        const input = button.closest(".password-field")?.querySelector("[data-password-toggle-input]");
        const icon = button.querySelector("i");
        if (!input) {
            return;
        }

        const showLabel = button.dataset.showLabel || button.getAttribute("aria-label") || "Show value";
        const hideLabel = button.dataset.hideLabel || "Hide value";

        const setVisible = (visible) => {
            input.type = visible ? "text" : "password";
            button.setAttribute("aria-pressed", String(visible));
            button.setAttribute("title", visible ? hideLabel : showLabel);
            button.setAttribute("aria-label", visible ? hideLabel : showLabel);
            icon?.classList.toggle("fa-eye", !visible);
            icon?.classList.toggle("fa-eye-slash", visible);
        };

        const syncAvailability = () => {
            const hasValue = input.value.length > 0;
            button.hidden = !hasValue;
            button.classList.toggle("is-visible", hasValue);
            if (!hasValue) {
                setVisible(false);
                if (document.activeElement === button) {
                    input.focus({ preventScroll: true });
                }
            }
        };

        button.dataset.passwordToggleBound = "true";
        setVisible(input.type === "text");
        syncAvailability();
        input.addEventListener("input", syncAvailability);
        input.addEventListener("change", syncAvailability);
        window.setTimeout(syncAvailability, 250);
        button.addEventListener("click", () => {
            setVisible(input.type !== "text");
            input.focus({ preventScroll: true });
        });
    });
});
