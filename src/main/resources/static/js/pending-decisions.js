(() => {
    const initialize = () => {
        const rows = document.querySelector("[data-pending-decision-rows]");
        if (!rows || !window.EnderVault) {
            return;
        }

        const updateEmptyState = () => {
            const count = rows.querySelectorAll("[data-pending-decision-id]").length;
            const badge = document.querySelector("[data-pending-decision-count]");
            const empty = document.querySelector("[data-pending-decision-empty]");
            if (badge) {
                badge.textContent = `${count} pending`;
            }
            if (empty) {
                empty.hidden = count !== 0;
            }
        };

        const resolve = async (form) => {
            const action = form.dataset.pendingAction;
            if (["REPLACE", "DISCARD"].includes(action)) {
                const confirmed = await window.EnderVault.askConfirmation({
                    title: action === "REPLACE" ? "Replace existing file" : "Discard pending file",
                    message: action === "REPLACE"
                        ? "Replace the existing destination file with this staged file?"
                        : "Permanently discard this staged file?",
                    confirmLabel: action === "REPLACE" ? "Replace" : "Discard",
                    danger: true
                });
                if (!confirmed) {
                    return;
                }
            }

            const submit = form.querySelector("button");
            submit?.setAttribute("disabled", "");
            try {
                const response = await window.EnderVault.submitJsonForm(form);
                window.EnderVault.showNotification(response.notification);
                document.querySelector(`[data-pending-decision-id="${CSS.escape(response.removedId)}"]`)?.remove();
                updateEmptyState();
                window.EnderVaultNotificationCenter?.refresh();
            } catch (error) {
                window.EnderVault.showToast("error", error.message || "Pending file resolution failed.");
            } finally {
                submit?.removeAttribute("disabled");
            }
        };

        rows.addEventListener("submit", (event) => {
            const form = event.target.closest("[data-pending-decision-form]");
            if (!form) {
                return;
            }
            event.preventDefault();
            void resolve(form);
        });

        rows.addEventListener("click", async (event) => {
            const button = event.target.closest("[data-pending-save-as]");
            if (!button) {
                return;
            }
            const form = button.closest("[data-pending-decision-form]");
            const filename = await window.EnderVault.askTextInput({
                title: "Save pending file as",
                message: "Choose a new name in the requested destination.",
                label: "File name",
                initialValue: button.dataset.originalFilename || "",
                confirmLabel: "Save"
            });
            if (!filename) {
                return;
            }
            form.elements.filename.value = filename;
            await resolve(form);
        });

        updateEmptyState();
    };

    document.readyState === "loading"
        ? document.addEventListener("DOMContentLoaded", initialize, { once: true })
        : initialize();
})();
