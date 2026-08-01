document.addEventListener("DOMContentLoaded", () => {
    const root = document.querySelector("[data-vpn-runtime-root]");
    if (!root || !window.EnderVault) {
        return;
    }

    const { requestJson, submitJsonForm, showNotification, showToast, askConfirmation, copyText } =
        window.EnderVault;
    let current = null;
    let requestInFlight = false;

    const setText = (name, value) => {
        root.querySelectorAll(`[data-vpn-runtime="${name}"]`).forEach((element) => {
            element.textContent = value;
        });
    };

    const setBadge = (name, label, statusClass) => {
        root.querySelectorAll(`[data-vpn-runtime="${name}"]`).forEach((element) => {
            element.className = `status-badge ${statusClass}`;
            element.textContent = label;
        });
    };

    const render = (vpn) => {
        if (!vpn) {
            return;
        }
        current = vpn;
        setBadge("controlBadge", vpn.label, vpn.statusClass);
        setBadge("healthBadge", vpn.health.label, vpn.health.statusClass);
        setBadge("routeBadge", vpn.routeLabel, vpn.vpnRouteSelected ? "active" : "info");
        setText("publicIp", vpn.publicIp);
        setText("profileName", vpn.profileName);
        setText("checkedAt", vpn.checkedAtLabel);
        setText("latency", vpn.latencyLabel);
        setText("detail", vpn.detail);
        setText("proxyEndpoint", vpn.health.proxyEndpoint);
        setText("healthDetail", vpn.health.detail);
        setText("activeVpnTasks", String(vpn.activeVpnTasks));

        root.querySelectorAll("[data-vpn-command]").forEach((form) => {
            const command = form.dataset.vpnCommand;
            const button = form.querySelector("button[type='submit']");
            if (!button) {
                return;
            }
            button.disabled = command !== "refresh" && (
                !vpn.controllable
                || (command === "connect" && vpn.running)
                || (command === "disconnect" && !vpn.running)
            );
        });

        const copyButton = root.querySelector("[data-copy-vpn-ip]");
        if (copyButton) {
            copyButton.disabled = !vpn.publicIp || vpn.publicIp === "Unavailable";
        }
    };

    const refresh = async ({ quiet = false } = {}) => {
        if (requestInFlight) {
            return;
        }
        requestInFlight = true;
        try {
            render(await requestJson(root.dataset.statusUrl));
        } catch (error) {
            if (!quiet) {
                showToast("error", error.message || "VPN status could not be refreshed.");
            }
        } finally {
            requestInFlight = false;
        }
    };

    root.querySelectorAll("form[data-vpn-command]").forEach((form) => {
        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            const command = form.dataset.vpnCommand;
            const disruptive = command === "disconnect" || command === "reconnect";
            const formData = new FormData(form);
            if (disruptive && current?.activeVpnTasks > 0) {
                const confirmed = await askConfirmation({
                    title: command === "disconnect" ? "Disconnect VPN" : "Reconnect VPN",
                    message: `${current.activeVpnTasks} VPN remote download task(s) are active and may fail. Continue?`,
                    confirmLabel: command === "disconnect" ? "Disconnect" : "Reconnect",
                    danger: true
                });
                if (!confirmed) {
                    return;
                }
                formData.set("force", "true");
            }

            const button = form.querySelector("button[type='submit']");
            button.disabled = true;
            try {
                const body = await submitJsonForm(form, formData);
                showNotification(body.notification);
                render(body.vpn);
            } catch (error) {
                showToast("error", error.message || "VPN control command failed.");
            } finally {
                if (current) {
                    render(current);
                } else {
                    button.disabled = false;
                }
            }
        });
    });

    root.querySelector("[data-copy-vpn-ip]")?.addEventListener("click", async () => {
        if (!current?.publicIp || current.publicIp === "Unavailable") {
            return;
        }
        if (await copyText(current.publicIp)) {
            showToast("success", "VPN public IP copied.");
        } else {
            showToast("error", "Copy failed.");
        }
    });

    refresh({ quiet: true });
    window.setInterval(() => {
        if (document.visibilityState === "visible") {
            refresh({ quiet: true });
        }
    }, 10000);
});
