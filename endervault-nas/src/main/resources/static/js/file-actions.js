document.addEventListener("DOMContentLoaded", () => {
    const bulkActionForm = document.getElementById("bulkActionForm");
    const deleteSelectedButton = document.getElementById("deleteSelectedButton");
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

    if (createDirectoryForm && directoryNameInput && createDirectoryButton) {
        createDirectoryButton.addEventListener("click", async () => {
            const directoryName = window.prompt("Directory name");
            if (!directoryName) {
                return;
            }

            const trimmedName = directoryName.trim();
            if (!trimmedName) {
                return;
            }

            directoryNameInput.disabled = false;
            directoryNameInput.value = trimmedName;
            createDirectoryButton.disabled = true;
            try {
                const body = await submitFormJson(createDirectoryForm);
                showActionNotification(body.notification);
                await window.EnderVaultFileBrowser.refreshListing(body.redirectUrl || window.location.href);
            } catch (error) {
                window.EnderVault.showToast("error", error.message || "Directory creation failed.");
            } finally {
                directoryNameInput.value = "";
                directoryNameInput.disabled = true;
                createDirectoryButton.disabled = false;
            }
        });
    }

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
