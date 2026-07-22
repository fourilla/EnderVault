document.addEventListener("DOMContentLoaded", () => {
    const bulkActionForm = document.getElementById("bulkActionForm");
    const deleteSelectedButton = document.getElementById("deleteSelectedButton");
    const createFileForm = document.getElementById("createFileForm");
    const fileNameInput = document.getElementById("newFileNameInput");
    const createFileButton = document.getElementById("createFileButton");
    const createDirectoryForm = document.getElementById("createDirectoryForm");
    const directoryNameInput = document.getElementById("newDirectoryNameInput");
    const createDirectoryButton = document.getElementById("createDirectoryButton");

    const showActionNotification = (notification) => {
        window.EnderVault.showNotification(notification);
    };

    const submitFormJson = async (form, action = form.action, method = form.method || "POST") =>
        window.EnderVault.requestJson(action, {
            method: method.toUpperCase(),
            body: new FormData(form)
        });

    const closeParentMenu = (button) => {
        const menu = button?.closest("details.settings-menu");
        if (menu) {
            menu.open = false;
        }
    };

    const createItem = ({ form, input, button, title, label, placeholder, failureFallback }) => {
        if (!form || !input || !button) {
            return;
        }

        button.addEventListener("click", async () => {
            const itemName = await window.EnderVault.askTextInput({
                title,
                label,
                placeholder,
                confirmLabel: "Create"
            });
            if (!itemName) {
                return;
            }

            const trimmedName = itemName.trim();
            if (!trimmedName) {
                return;
            }

            input.disabled = false;
            input.value = trimmedName;
            button.disabled = true;
            try {
                const body = await submitFormJson(form);
                showActionNotification(body.notification);
                await window.EnderVaultFileBrowser.refreshListing(body.redirectUrl || window.location.href);
                closeParentMenu(button);
            } catch (error) {
                window.EnderVault.showToast("error", error.message || failureFallback);
            } finally {
                input.value = "";
                input.disabled = true;
                button.disabled = false;
            }
        });
    };

    createItem({
        form: createFileForm,
        input: fileNameInput,
        button: createFileButton,
        title: "New file",
        label: "File name",
        placeholder: "note.txt",
        failureFallback: "File creation failed."
    });

    createItem({
        form: createDirectoryForm,
        input: directoryNameInput,
        button: createDirectoryButton,
        title: "New directory",
        label: "Directory name",
        placeholder: "New directory",
        failureFallback: "Directory creation failed."
    });

    if (bulkActionForm && deleteSelectedButton) {
        deleteSelectedButton.addEventListener("click", async (event) => {
            if (deleteSelectedButton.disabled) {
                return;
            }

            event.preventDefault();
            deleteSelectedButton.disabled = true;
            try {
                const body = await submitFormJson(
                        bulkActionForm,
                        deleteSelectedButton.formAction || deleteSelectedButton.getAttribute("formaction"),
                        deleteSelectedButton.formMethod || deleteSelectedButton.getAttribute("formmethod") || "POST"
                );
                showActionNotification(body.notification);
                if (body.task) {
                    window.EnderVaultServerTasks?.track(body.task, {
                        refreshUrl: body.redirectUrl || window.location.href
                    });
                } else {
                    await window.EnderVaultFileBrowser.refreshListing(body.redirectUrl || window.location.href);
                }
            } catch (error) {
                window.EnderVault.showToast("error", error.message || "Delete failed.");
            } finally {
                deleteSelectedButton.disabled = false;
                window.EnderVaultFileSelection?.update();
            }
        });
    }
});
