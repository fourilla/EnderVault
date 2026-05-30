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

    const createDirectoryForm = document.getElementById("createDirectoryForm");
    const directoryNameInput = document.getElementById("newDirectoryNameInput");
    const createDirectoryButton = document.getElementById("createDirectoryButton");

    if (createDirectoryForm && directoryNameInput && createDirectoryButton) {
        createDirectoryButton.addEventListener("click", () => {
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
            createDirectoryForm.submit();
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
