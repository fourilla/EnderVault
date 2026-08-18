document.addEventListener("DOMContentLoaded", () => {
    const dialog = document.getElementById("archiveCreationDialog");
    const form = dialog?.querySelector("[data-archive-create-form]");
    const toolbarButton = document.getElementById("compressSelectedButton");
    const outputNameInput = dialog?.querySelector("[data-archive-output-name]");
    const selectedInputs = dialog?.querySelector("[data-archive-selected-inputs]");
    const selectionSummary = dialog?.querySelector("[data-archive-selection-summary]");
    const submitButton = dialog?.querySelector("[data-archive-create-submit]");

    if (!dialog || !form || !outputNameInput || !selectedInputs || !selectionSummary || !submitButton) {
        return;
    }

    const selectedItems = () => {
        const checkboxes = window.EnderVaultFileSelection?.selectedItemCheckboxes?.() || [];
        return checkboxes
                .filter((checkbox) => checkbox.checked)
                .map((checkbox) => {
                    const item = checkbox.closest("[data-context-item='true']");
                    return {
                        name: checkbox.value,
                        directory: item?.dataset.itemKind === "directory"
                    };
                });
    };

    const withoutExtension = (name) => {
        const index = name.lastIndexOf(".");
        return index > 0 ? name.slice(0, index) : name;
    };

    const suggestedName = (items) => {
        if (items.length !== 1) {
            return "Archive.zip";
        }
        const item = items[0];
        const stem = item.directory ? item.name : withoutExtension(item.name);
        const suggested = `${stem}.zip`;
        return suggested.toLowerCase() === item.name.toLowerCase()
                ? `${stem} - compressed.zip`
                : suggested;
    };

    const replaceSelectedInputs = (items) => {
        selectedInputs.replaceChildren(...items.map((item) => {
            const input = document.createElement("input");
            input.type = "hidden";
            input.name = "items";
            input.value = item.name;
            return input;
        }));
    };

    const open = (items = selectedItems()) => {
        const safeItems = Array.isArray(items)
                ? items.filter((item) => item?.name)
                : [];
        if (safeItems.length === 0) {
            window.EnderVault.showToast("warning", "Select at least one item to compress.");
            return;
        }

        replaceSelectedInputs(safeItems);
        outputNameInput.value = suggestedName(safeItems);
        selectionSummary.textContent = safeItems.length === 1
                ? `Compress ${safeItems[0].name} in the current directory.`
                : `Compress ${safeItems.length} selected items in the current directory.`;
        dialog.returnValue = "cancel";
        dialog.showModal();
        outputNameInput.focus();
        outputNameInput.select();
    };

    dialog.querySelectorAll("[data-archive-create-close]").forEach((button) => {
        button.addEventListener("click", () => dialog.close("cancel"));
    });

    toolbarButton?.addEventListener("click", () => open());

    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        submitButton.disabled = true;
        try {
            const body = await window.EnderVault.requestJson(form.action, {
                method: "POST",
                body: new FormData(form)
            });
            window.EnderVault.showNotification(body.notification);
            if (body.task) {
                window.EnderVaultServerTasks?.track(body.task, {
                    refreshUrl: body.redirectUrl || window.location.href
                });
            }
            window.EnderVaultFileSelection?.clear();
            dialog.close("queued");
        } catch (error) {
            window.EnderVault.showToast("error", error.message || "ZIP creation could not be queued.");
        } finally {
            submitButton.disabled = false;
        }
    });

    window.EnderVaultArchiveCreation = { open };
});
