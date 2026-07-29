document.addEventListener("DOMContentLoaded", () => {
    const form = document.querySelector("[data-outbound-route-form]");
    if (!form || !window.EnderVault) {
        return;
    }

    const button = form.querySelector("[data-outbound-route-toggle]");
    const routeInput = form.querySelector("[data-outbound-route-input]");
    const icon = form.querySelector("[data-outbound-route-icon]");
    const label = form.querySelector("[data-outbound-route-label]");

    const applyRoute = (route) => {
        if (!route) {
            return;
        }
        routeInput.value = route.nextRoute;
        icon.className = route.iconClass;
        label.textContent = route.label;
        button.title = route.title;
        button.setAttribute("aria-label", route.title);
        button.classList.remove("route-direct", "route-vpn-ready", "route-vpn-unavailable");
        button.classList.add(`route-${route.statusClass}`);
    };

    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        if (button.disabled) {
            return;
        }
        button.disabled = true;
        try {
            const payload = await window.EnderVault.submitJsonForm(form);
            applyRoute(payload.route);
            window.EnderVault.showNotification(payload.notification);
        } catch (error) {
            window.EnderVault.showToast(
                "error",
                error.message || "The outbound route could not be changed."
            );
        } finally {
            button.disabled = false;
        }
    });
});
