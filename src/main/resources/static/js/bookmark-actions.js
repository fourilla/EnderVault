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
    const selectionForm = document.getElementById("bulkActionForm");
    const deleteSelectedButton = document.getElementById("deleteSelectedButton");

    const showResult = async (body) => {
        window.EnderVault.showNotification(body.notification);
        if (body.redirectUrl && window.EnderVaultFileBrowser?.refreshListing) {
            await window.EnderVaultFileBrowser.refreshListing(body.redirectUrl);
        }
    };

    const submitForm = async (form) => window.EnderVault.submitJsonForm(form);

    const submitAction = async (url, fields = {}) => {
        const formData = new FormData();
        const csrf = window.EnderVault.csrfPair(selectionForm || document);
        if (csrf) {
            formData.append(csrf.name, csrf.value);
        }
        Object.entries(fields).forEach(([name, value]) => formData.append(name, value == null ? "" : value));
        return window.EnderVault.requestJson(url, {
            method: "POST",
            body: formData
        });
    };

    const refreshFromResponse = async (body) => {
        window.EnderVault.showNotification(body.notification);
        if (window.EnderVaultFileBrowser?.refreshListing) {
            await window.EnderVaultFileBrowser.refreshListing(body.redirectUrl || window.location.href);
        }
    };

    const deleteItem = async (item) => {
        if (!window.confirm(`Delete "${item.title}"?`)) {
            return;
        }
        const body = await submitAction(item.deleteUrl || "/api/v1/bookmarks/delete", {
            id: item.id,
            parentId: selectionForm?.querySelector('input[name="parentId"]')?.value || "",
            q: selectionForm?.querySelector('input[name="q"]')?.value || ""
        });
        await refreshFromResponse(body);
    };

    const deleteSelected = async (expectedCount = null) => {
        if (!selectionForm) {
            return;
        }
        const selectedCount = Array.from(document.querySelectorAll(
                'input[name="bookmarkIds"][form="bulkActionForm"]:checked'
        )).length;
        const count = expectedCount == null ? selectedCount : expectedCount;
        if (count === 0 || !window.confirm(`Delete ${count} selected bookmark items?`)) {
            return;
        }

        const body = await window.EnderVault.requestJson(
                selectionForm.dataset.bookmarkDeleteSelectedUrl || "/api/v1/bookmarks/delete-selected",
                {
                    method: "POST",
                    body: new FormData(selectionForm)
                }
        );
        await refreshFromResponse(body);
        window.EnderVaultFileSelection?.clear();
    };

    const refreshMetadata = async (item) => {
        const body = await submitAction(item.metadataUrl || "/api/v1/bookmarks/metadata", {
            id: item.id,
            parentId: selectionForm?.querySelector('input[name="parentId"]')?.value || "",
            q: selectionForm?.querySelector('input[name="q"]')?.value || ""
        });
        await refreshFromResponse(body);
    };

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

    deleteSelectedButton?.addEventListener("click", async (event) => {
        event.preventDefault();
        deleteSelectedButton.disabled = true;
        try {
            await deleteSelected();
        } catch (error) {
            window.EnderVault.showToast("error", error.message || "Bookmark delete failed.");
        } finally {
            deleteSelectedButton.disabled = false;
            window.EnderVaultFileSelection?.update();
        }
    });

    window.EnderVaultBookmarks = {
        deleteItem,
        deleteSelected,
        refreshMetadata
    };
});
