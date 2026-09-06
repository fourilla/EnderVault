document.addEventListener("DOMContentLoaded", () => {
    const dialog = document.querySelector("[data-storage-directory-picker]");
    const tree = dialog?.querySelector("[data-directory-picker-tree]");
    const selectionLabel = dialog?.querySelector("[data-directory-picker-selection]");
    const { requestJson, showNotification } = window.EnderVault || {};
    const directoryTreeFactory = window.EnderVaultDirectoryTree;
    if (!dialog || !tree || !requestJson || !directoryTreeFactory) {
        return;
    }

    let targetInput = null;

    const entriesUrl = (path) => {
        const url = new URL(dialog.dataset.entriesUrl, window.location.origin);
        url.searchParams.set("path", directoryTreeFactory.normalizePath(path));
        url.searchParams.set("types", "directory");
        return url.toString();
    };

    const directoryTree = directoryTreeFactory.create(tree, {
        async loadEntries(path) {
            const payload = await requestJson(entriesUrl(path));
            return payload.entries;
        },
        onSelect(path) {
            if (selectionLabel) {
                selectionLabel.textContent = path ? `/${path}` : "/";
            }
        },
        onError(error) {
            showNotification?.({ type: "error", message: error.message || "Directories could not be loaded." });
        }
    });

    const close = () => {
        targetInput = null;
        window.EnderVault.closeDialog(dialog);
    };

    document.addEventListener("click", async (event) => {
        const opener = event.target.closest("[data-directory-picker-open]");
        if (!opener) {
            return;
        }
        event.preventDefault();
        targetInput = document.getElementById(opener.dataset.directoryPickerTarget);
        if (!targetInput) {
            return;
        }
        window.EnderVault.openDialog(dialog);
        await directoryTree.render(targetInput.value);
    });

    dialog.querySelectorAll("[data-directory-picker-close]").forEach((button) => {
        button.addEventListener("click", close);
    });
    dialog.querySelector("[data-directory-picker-apply]")?.addEventListener("click", () => {
        if (!targetInput) {
            return;
        }
        targetInput.value = directoryTree.selection();
        targetInput.dispatchEvent(new Event("input", { bubbles: true }));
        targetInput.dispatchEvent(new Event("change", { bubbles: true }));
        close();
    });
    dialog.addEventListener("close", () => {
        if (!dialog.open) targetInput = null;
    });
});
