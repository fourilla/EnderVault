document.addEventListener("DOMContentLoaded", () => {
    const uploadForm = document.getElementById("uploadForm");
    const fileUploadInput = document.getElementById("fileUploadInput");
    const uploadButton = document.getElementById("uploadButton");
    const bulkActionForm = document.getElementById("bulkActionForm");
    const deleteSelectedButton = document.getElementById("deleteSelectedButton");
    const dropZone = document.querySelector("[data-file-dropzone]");
    const dropUploadOverlay = document.getElementById("dropUploadOverlay");
    const selectionButtons = [
        document.getElementById("downloadSelectedButton"),
        deleteSelectedButton
    ].filter(Boolean);

    const selectedItemCheckboxes = () =>
        Array.from(document.querySelectorAll('input[name="items"][form="bulkActionForm"]'));

    const updateSelectionActions = () => {
        const hasSelection = selectedItemCheckboxes().some((checkbox) => checkbox.checked);
        selectionButtons.forEach((button) => {
            button.disabled = !hasSelection;
            button.title = hasSelection ? button.dataset.readyTitle : "Select items first";
        });
    };

    if (uploadForm && fileUploadInput && uploadButton) {
        uploadButton.addEventListener("click", () => fileUploadInput.click());
        fileUploadInput.addEventListener("change", () => {
            if (fileUploadInput.files.length > 0) {
                uploadFormWithProgress(fileUploadInput.files.length);
            }
        });
    }

    const showUploadStatus = (message, error = false) => {
        if (window.EnderVaultToasts) {
            window.EnderVaultToasts.show({
                type: error ? "error" : "info",
                message
            });
        }
    };

    const showActionNotification = (notification) => {
        if (!notification || !window.EnderVaultToasts) {
            return;
        }
        window.EnderVaultToasts.show({
            type: notification.type,
            message: notification.message,
            actionLabel: notification.actionLabel || "Copy",
            actionValue: notification.actionValue || ""
        });
    };

    const submitFormJson = async (form, action = form.action, method = form.method || "POST") => {
        const response = await fetch(action, {
            method: method.toUpperCase(),
            body: new FormData(form),
            headers: {
                "Accept": "application/json",
                "X-Requested-With": "fetch"
            },
            credentials: "same-origin"
        });
        const contentType = response.headers.get("content-type") || "";
        const body = contentType.includes("application/json") ? await response.json() : null;
        if (!response.ok || !body || body.ok === false) {
            throw new Error(body?.notification?.message || "The action failed.");
        }
        return body;
    };

    const syncToolbarState = (targetUrl) => {
        const values = {
            path: targetUrl.searchParams.get("path") || "",
            view: targetUrl.searchParams.get("view"),
            sort: targetUrl.searchParams.get("sort"),
            dir: targetUrl.searchParams.get("dir"),
            page: targetUrl.searchParams.get("page") || "1",
            size: targetUrl.searchParams.get("size")
        };

        Object.entries(values).forEach(([name, value]) => {
            if (value === null) {
                return;
            }
            document.querySelectorAll(`input[type="hidden"][name="${name}"]`).forEach((input) => {
                input.value = value;
            });
        });
    };

    const refreshListing = async (url = window.location.href) => {
        const targetUrl = new URL(url, window.location.href);
        const response = await fetch(targetUrl, {
            headers: { "X-Requested-With": "fetch" },
            credentials: "same-origin"
        });
        if (!response.ok) {
            return;
        }

        const documentText = await response.text();
        const nextDocument = new DOMParser().parseFromString(documentText, "text/html");
        const currentMain = document.querySelector("main.workspace");
        const nextMain = nextDocument.querySelector("main.workspace");
        if (!currentMain || !nextMain) {
            return;
        }

        currentMain.querySelectorAll(".browser-section, .browser-grid-empty").forEach((element) => element.remove());
        const nextNodes = Array.from(nextMain.children)
                .filter((element) => element.matches(".browser-section, .browser-grid-empty"));
        nextNodes.forEach((node) => currentMain.append(document.importNode(node, true)));
        if (targetUrl.href !== window.location.href) {
            window.history.replaceState({}, "", targetUrl);
        }
        syncToolbarState(targetUrl);
        updateSelectionActions();
    };

    function uploadFormWithProgress(fileCount) {
        const formData = new FormData(uploadForm);
        const xhr = new XMLHttpRequest();

        xhr.open((uploadForm.method || "POST").toUpperCase(), uploadForm.action);
        xhr.setRequestHeader("Accept", "application/json");
        xhr.setRequestHeader("X-Requested-With", "XMLHttpRequest");

        xhr.upload.onprogress = (event) => {
            if (!event.lengthComputable) {
                return;
            }
            const percent = Math.round((event.loaded / event.total) * 100);
            uploadButton.title = `Uploading ${percent}%`;
        };

        xhr.onload = async () => {
            fileUploadInput.value = "";
            uploadButton.title = uploadButton.dataset.readyTitle || "Upload files";

            let body = null;
            try {
                body = JSON.parse(xhr.responseText || "{}");
            } catch (error) {
                showUploadStatus("Upload finished, but the server response could not be read.", true);
                return;
            }

            if (xhr.status >= 200 && xhr.status < 300 && body.ok !== false) {
                showActionNotification(body.notification);
                await refreshListing(body.redirectUrl || window.location.href);
                return;
            }

            const message = body.notification?.message || "Upload failed.";
            showUploadStatus(message, true);
        };

        xhr.onerror = () => {
            fileUploadInput.value = "";
            uploadButton.title = uploadButton.dataset.readyTitle || "Upload files";
            showUploadStatus("Upload failed.", true);
        };

        showUploadStatus(`Uploading ${fileCount} file${fileCount === 1 ? "" : "s"}...`);
        xhr.send(formData);
    }

    if (uploadForm && fileUploadInput && uploadButton && dropZone) {
        let dragDepth = 0;
        let isInternalDrag = false;
        const browserItemSelector = ".browser-card, .table-wrap a, .thumb-media, .video-thumb-wrap, .thumb-placeholder";

        const transferTypes = (dataTransfer) => Array.from((dataTransfer && dataTransfer.types) || []);

        const isFileTransfer = (dataTransfer) => {
            if (isInternalDrag || !dataTransfer || !transferTypes(dataTransfer).includes("Files")) {
                return false;
            }

            const items = Array.from(dataTransfer.items || []);
            return items.length === 0 || items.some((item) => item.kind === "file");
        };

        const showDropOverlay = () => {
            dropZone.classList.add("drag-upload-active");
            if (dropUploadOverlay) {
                dropUploadOverlay.setAttribute("aria-hidden", "false");
            }
        };

        const hideDropOverlay = () => {
            dragDepth = 0;
            dropZone.classList.remove("drag-upload-active");
            if (dropUploadOverlay) {
                dropUploadOverlay.setAttribute("aria-hidden", "true");
            }
        };

        const resetDragState = () => {
            isInternalDrag = false;
            hideDropOverlay();
        };

        const collectDroppedFiles = (dataTransfer) => {
            const items = Array.from(dataTransfer.items || []);
            let hasDirectory = false;

            if (items.length === 0) {
                return {
                    files: Array.from(dataTransfer.files || []),
                    hasDirectory
                };
            }

            const files = items.flatMap((item) => {
                if (item.kind !== "file") {
                    return [];
                }

                const entry = typeof item.webkitGetAsEntry === "function"
                    ? item.webkitGetAsEntry()
                    : null;
                if (entry && entry.isDirectory) {
                    hasDirectory = true;
                    return [];
                }

                const file = item.getAsFile();
                return file ? [file] : [];
            });

            return { files, hasDirectory };
        };

        const assignDroppedFiles = (files, dataTransfer) => {
            if (files.length === 0) {
                return false;
            }

            if (dataTransfer.files && dataTransfer.files.length === files.length) {
                fileUploadInput.files = dataTransfer.files;
                return true;
            }

            if (typeof DataTransfer !== "function") {
                return false;
            }

            const filteredTransfer = new DataTransfer();
            files.forEach((file) => filteredTransfer.items.add(file));
            fileUploadInput.files = filteredTransfer.files;
            return true;
        };

        const uploadDroppedFiles = (dataTransfer) => {
            const { files, hasDirectory } = collectDroppedFiles(dataTransfer);

            if (files.length === 0) {
                const message = hasDirectory
                    ? "Directory uploads are not supported yet."
                    : "Drop local files to upload.";
                showUploadStatus(message, true);
                return;
            }

            if (!assignDroppedFiles(files, dataTransfer)) {
                showUploadStatus("This browser cannot attach dropped files to the upload form.", true);
                return;
            }

            const suffix = hasDirectory ? " Directory items were skipped." : "";
            if (suffix) {
                showUploadStatus(suffix.trim(), true);
            }
            uploadFormWithProgress(files.length);
        };

        dropZone.addEventListener("dragstart", (event) => {
            isInternalDrag = true;
            hideDropOverlay();

            if (event.target.closest(browserItemSelector)) {
                event.preventDefault();
                window.setTimeout(resetDragState, 0);
            }
        });

        dropZone.addEventListener("dragenter", (event) => {
            if (!isFileTransfer(event.dataTransfer)) {
                return;
            }

            event.preventDefault();
            dragDepth += 1;
            showDropOverlay();
        });

        dropZone.addEventListener("dragover", (event) => {
            if (!isFileTransfer(event.dataTransfer)) {
                return;
            }

            event.preventDefault();
            event.dataTransfer.dropEffect = "copy";
            showDropOverlay();
        });

        dropZone.addEventListener("dragleave", (event) => {
            if (!isFileTransfer(event.dataTransfer)) {
                return;
            }

            dragDepth -= 1;
            if (dragDepth <= 0) {
                hideDropOverlay();
            }
        });

        dropZone.addEventListener("drop", (event) => {
            if (!isFileTransfer(event.dataTransfer)) {
                return;
            }

            event.preventDefault();
            event.stopPropagation();
            hideDropOverlay();
            uploadDroppedFiles(event.dataTransfer);
        });

        document.addEventListener("dragover", (event) => {
            if (isFileTransfer(event.dataTransfer)) {
                event.preventDefault();
            }
        });

        document.addEventListener("drop", (event) => {
            if (isInternalDrag) {
                event.preventDefault();
                resetDragState();
                return;
            }

            if (!isFileTransfer(event.dataTransfer) || dropZone.contains(event.target)) {
                return;
            }

            event.preventDefault();
            hideDropOverlay();
        });

        document.addEventListener("dragend", resetDragState);
        window.addEventListener("blur", resetDragState);
    }

    const createDirectoryForm = document.getElementById("createDirectoryForm");
    const directoryNameInput = document.getElementById("newDirectoryNameInput");
    const createDirectoryButton = document.getElementById("createDirectoryButton");

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
                await refreshListing(body.redirectUrl || window.location.href);
            } catch (error) {
                showUploadStatus(error.message || "Directory creation failed.", true);
            } finally {
                directoryNameInput.value = "";
                directoryNameInput.disabled = true;
                createDirectoryButton.disabled = false;
            }
        });
    }

    if (selectionButtons.length > 0) {
        selectionButtons.forEach((button) => {
            button.dataset.readyTitle = button.title;
        });
        document.addEventListener("change", (event) => {
            if (event.target.matches('input[name="items"][form="bulkActionForm"]')) {
                updateSelectionActions();
            }
        });
        updateSelectionActions();
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
                await refreshListing(body.redirectUrl || window.location.href);
            } catch (error) {
                showUploadStatus(error.message || "Delete failed.", true);
            } finally {
                deleteSelectedButton.disabled = false;
                updateSelectionActions();
            }
        });
    }
});
