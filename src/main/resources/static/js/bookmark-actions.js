document.addEventListener("DOMContentLoaded", () => {
    if (!window.EnderVault) {
        return;
    }

    const directoryButton = document.getElementById("createBookmarkDirectoryButton");
    const directoryForm = document.getElementById("createBookmarkDirectoryForm");
    const directoryNameInput = document.getElementById("newBookmarkDirectoryNameInput");
    const linkButton = document.getElementById("createBookmarkLinkButton");
    const linkDialog = document.getElementById("bookmarkLinkDialog");
    const linkForm = document.getElementById("createBookmarkLinkForm");
    const bulkButton = document.getElementById("bulkAddBookmarksButton");
    const bulkDialog = document.getElementById("bookmarkBulkAddDialog");
    const bulkForm = document.getElementById("bookmarkBulkAddForm");

    const showResult = async (body) => {
        window.EnderVault.showNotification(body.notification);
        if (body.redirectUrl && window.EnderVaultFileBrowser?.refreshListing) {
            await window.EnderVaultFileBrowser.refreshListing(body.redirectUrl);
        }
    };

    const submitForm = async (form) => window.EnderVault.submitJsonForm(form);

    const setBusy = (form, busy) => {
        if (busy) {
            form?.setAttribute("aria-busy", "true");
        } else {
            form?.removeAttribute("aria-busy");
        }
        form?.querySelectorAll('button[type="submit"]').forEach((button) => {
            button.disabled = busy;
        });
    };

    if (directoryButton && directoryForm && directoryNameInput) {
        directoryButton.addEventListener("click", async () => {
            const directoryName = await window.EnderVault.askTextInput({
                title: "New directory",
                label: "Directory name",
                placeholder: "New directory",
                confirmLabel: "Create"
            });
            if (!directoryName) {
                return;
            }

            const trimmedName = directoryName.trim();
            if (!trimmedName) {
                return;
            }

            directoryNameInput.disabled = false;
            directoryNameInput.value = trimmedName;
            directoryButton.disabled = true;
            try {
                await showResult(await submitForm(directoryForm));
            } catch (error) {
                window.EnderVault.showToast("error", error.message || "Bookmark directory creation failed.");
            } finally {
                directoryNameInput.value = "";
                directoryNameInput.disabled = true;
                directoryButton.disabled = false;
            }
        });
    }

    const bindDialog = ({ button, dialog, form, focusSelector, onSuccess, failureMessage }) => {
        if (!button || !dialog || !form) {
            return;
        }

        button.addEventListener("click", () => {
            form.reset();
            if (typeof dialog.showModal !== "function") {
                window.EnderVault.showToast("error", "This browser does not support modal dialogs.");
                return;
            }
            dialog.showModal();
            window.requestAnimationFrame(() => form.querySelector(focusSelector)?.focus());
        });

        dialog.querySelectorAll("[data-bookmark-dialog-close]").forEach((closeButton) => {
            closeButton.addEventListener("click", () => dialog.close("cancel"));
        });

        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            if (!form.reportValidity()) {
                return;
            }

            setBusy(form, true);
            try {
                const body = await submitForm(form);
                window.EnderVault.showNotification(body.notification);
                await onSuccess(body);
                dialog.close("success");
                form.reset();
            } catch (error) {
                window.EnderVault.showToast("error", error.message || failureMessage);
            } finally {
                setBusy(form, false);
            }
        });
    };

    bindDialog({
        button: linkButton,
        dialog: linkDialog,
        form: linkForm,
        focusSelector: 'input[name="url"]',
        onSuccess: async (body) => {
            if (body.redirectUrl && window.EnderVaultFileBrowser?.refreshListing) {
                await window.EnderVaultFileBrowser.refreshListing(body.redirectUrl);
            }
        },
        failureMessage: "Bookmark link creation failed."
    });

    bindDialog({
        button: bulkButton,
        dialog: bulkDialog,
        form: bulkForm,
        focusSelector: 'textarea[name="bulkText"]',
        onSuccess: async (body) => {
            if (body.task) {
                window.EnderVaultServerTasks?.track(body.task, {
                    refreshUrl: window.location.href
                });
            }
        },
        failureMessage: "Bookmark bulk add failed."
    });
});
