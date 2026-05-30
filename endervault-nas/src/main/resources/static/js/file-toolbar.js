document.addEventListener("DOMContentLoaded", () => {
    const uploadForm = document.getElementById("uploadForm");
    const fileUploadInput = document.getElementById("fileUploadInput");
    const uploadButton = document.getElementById("uploadButton");

    if (uploadForm && fileUploadInput && uploadButton) {
        uploadButton.addEventListener("click", () => fileUploadInput.click());
        fileUploadInput.addEventListener("change", () => {
            if (fileUploadInput.files.length > 0) {
                uploadForm.submit();
            }
        });
    }

    const createFolderForm = document.getElementById("createFolderForm");
    const folderNameInput = document.getElementById("newFolderNameInput");
    const createFolderButton = document.getElementById("createFolderButton");

    if (createFolderForm && folderNameInput && createFolderButton) {
        createFolderButton.addEventListener("click", () => {
            const folderName = window.prompt("Folder name");
            if (!folderName) {
                return;
            }

            const trimmedName = folderName.trim();
            if (!trimmedName) {
                return;
            }

            folderNameInput.disabled = false;
            folderNameInput.value = trimmedName;
            createFolderForm.submit();
        });
    }

    const itemCheckboxes = Array.from(document.querySelectorAll('input[name="items"][form="bulkActionForm"]'));
    const selectionButtons = [
        document.getElementById("downloadSelectedButton"),
        document.getElementById("deleteSelectedButton")
    ].filter(Boolean);

    if (selectionButtons.length > 0) {
        const updateSelectionActions = () => {
            const hasSelection = itemCheckboxes.some((checkbox) => checkbox.checked);
            selectionButtons.forEach((button) => {
                button.disabled = !hasSelection;
                button.title = hasSelection ? button.dataset.readyTitle : "Select items first";
            });
        };

        selectionButtons.forEach((button) => {
            button.dataset.readyTitle = button.title;
        });
        itemCheckboxes.forEach((checkbox) => {
            checkbox.addEventListener("change", updateSelectionActions);
        });
        updateSelectionActions();
    }
});
