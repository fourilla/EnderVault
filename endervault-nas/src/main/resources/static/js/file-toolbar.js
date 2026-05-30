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
    const selectableItemSelector = ".browser-card, tbody tr";
    const longPressDelayMs = 520;
    const longPressMoveTolerance = 10;
    const uploadState = {
        uploads: new Map(),
        nextId: 1,
        minimized: false,
        refreshTimer: null,
        pendingRefreshUrl: null,
        panel: null,
        list: null,
        summary: null,
        toggleButton: null
    };
    const selectionState = {
        active: false,
        longPressTimer: null,
        longPressItem: null,
        pointerId: null,
        pointerStartX: 0,
        pointerStartY: 0,
        suppressNextClick: false
    };

    document.body.classList.add("js-selection-enhanced");

    const selectedItemCheckboxes = () =>
        Array.from(document.querySelectorAll('input[name="items"][form="bulkActionForm"]'));

    const selectAllCheckboxes = () =>
        Array.from(document.querySelectorAll("[data-select-all]"));

    const selectPickLabels = () =>
        Array.from(document.querySelectorAll("[data-select-pick-label]"));

    const checkboxForItem = (item) =>
        item?.querySelector('input[name="items"][form="bulkActionForm"]') || null;

    const selectableItemFromTarget = (target) => {
        const item = target.closest(selectableItemSelector);
        return checkboxForItem(item) ? item : null;
    };

    const primaryLinkForItem = (item) => item.querySelector("a[href]");

    const isNativeControlTarget = (target) =>
        Boolean(target.closest("button, input, label, select, textarea, summary"));

    const isSelectionControlTarget = (target) =>
        Boolean(target.closest('input[name="items"][form="bulkActionForm"], [data-select-all], .select-all-label'));

    const setSelectionMode = (active) => {
        selectionState.active = active;
        document.body.classList.toggle("selection-mode-active", active);
    };

    const clearSelection = () => {
        selectedItemCheckboxes().forEach((checkbox) => {
            checkbox.checked = false;
        });
    };

    const syncSelectableItemState = () => {
        selectedItemCheckboxes().forEach((checkbox) => {
            const item = checkbox.closest(selectableItemSelector);
            if (!item) {
                return;
            }
            item.classList.toggle("is-selected", checkbox.checked);
            item.setAttribute("aria-selected", checkbox.checked ? "true" : "false");
        });
    };

    const updateSelectionActions = () => {
        const checkboxes = selectedItemCheckboxes();
        const selectedCount = checkboxes.filter((checkbox) => checkbox.checked).length;
        const hasSelection = selectedCount > 0;

        syncSelectableItemState();
        setSelectionMode(hasSelection);

        selectPickLabels().forEach((label) => {
            label.hidden = true;
        });

        selectAllCheckboxes().forEach((checkbox) => {
            checkbox.disabled = checkboxes.length === 0;
            checkbox.checked = checkboxes.length > 0 && selectedCount === checkboxes.length;
            checkbox.indeterminate = selectedCount > 0 && selectedCount < checkboxes.length;
        });

        selectionButtons.forEach((button) => {
            button.disabled = !hasSelection;
            button.title = hasSelection ? button.dataset.readyTitle : "Select items first";
        });
    };

    const toggleItemSelection = (item) => {
        const checkbox = checkboxForItem(item);
        if (!checkbox) {
            return;
        }

        checkbox.checked = !checkbox.checked;
        updateSelectionActions();
    };

    const exitSelectionMode = () => {
        clearSelection();
        updateSelectionActions();
    };

    const cancelLongPress = () => {
        window.clearTimeout(selectionState.longPressTimer);
        selectionState.longPressTimer = null;
        selectionState.longPressItem = null;
        selectionState.pointerId = null;
    };

    if (uploadForm && fileUploadInput && uploadButton) {
        uploadButton.dataset.readyTitle = uploadButton.title;
        uploadButton.addEventListener("click", () => fileUploadInput.click());
        fileUploadInput.addEventListener("change", () => {
            if (fileUploadInput.files.length > 0) {
                startFileUploads(Array.from(fileUploadInput.files));
                fileUploadInput.value = "";
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

    const requestListingRefresh = (url = window.location.href) => {
        uploadState.pendingRefreshUrl = url;
        window.clearTimeout(uploadState.refreshTimer);
        uploadState.refreshTimer = window.setTimeout(async () => {
            try {
                await refreshListing(uploadState.pendingRefreshUrl || window.location.href);
            } catch (error) {
                showUploadStatus("Upload finished, but the file list could not be refreshed.", true);
            }
        }, 450);
    };

    const formatBytes = (bytes) => {
        if (!Number.isFinite(bytes) || bytes <= 0) {
            return "0 B";
        }

        const units = ["B", "KB", "MB", "GB", "TB"];
        let value = bytes;
        let unitIndex = 0;
        while (value >= 1024 && unitIndex < units.length - 1) {
            value /= 1024;
            unitIndex += 1;
        }
        const precision = value >= 10 || unitIndex === 0 ? 0 : 1;
        return `${value.toFixed(precision)} ${units[unitIndex]}`;
    };

    const activeUploads = () =>
        Array.from(uploadState.uploads.values()).filter((upload) => upload.status === "uploading");

    const terminalUploads = () =>
        Array.from(uploadState.uploads.values()).filter((upload) => upload.status !== "uploading");

    const uploadPercent = (upload) => {
        if (!upload.total) {
            return 0;
        }
        return Math.max(0, Math.min(100, Math.round((upload.loaded / upload.total) * 100)));
    };

    const uploadStatusText = (upload) => {
        if (upload.cancelRequested && upload.status === "uploading") {
            return "Canceling...";
        }

        switch (upload.status) {
            case "complete":
                return "Complete";
            case "failed":
                return upload.message || "Failed";
            case "canceled":
                return "Canceled";
            default:
                return `${formatBytes(upload.loaded)} / ${formatBytes(upload.total || upload.file.size)}`;
        }
    };

    const ensureUploadPanel = () => {
        if (uploadState.panel) {
            uploadState.panel.hidden = false;
            return;
        }

        const panel = document.createElement("section");
        panel.className = "upload-activity js-only";
        panel.setAttribute("aria-label", "Upload activity");
        panel.hidden = true;
        panel.innerHTML = `
            <header class="upload-activity-header">
                <div class="upload-activity-heading">
                    <i class="fas fa-cloud-arrow-up" aria-hidden="true"></i>
                    <div>
                        <strong>Uploads</strong>
                        <span class="upload-activity-summary">Preparing...</span>
                    </div>
                </div>
                <button class="ghost icon-button upload-activity-toggle" type="button"
                        title="Minimize uploads" aria-label="Minimize uploads">
                    <i class="fas fa-minus" aria-hidden="true"></i>
                </button>
            </header>
            <div class="upload-activity-body">
                <div class="upload-list"></div>
            </div>
        `;
        document.body.append(panel);

        uploadState.panel = panel;
        uploadState.list = panel.querySelector(".upload-list");
        uploadState.summary = panel.querySelector(".upload-activity-summary");
        uploadState.toggleButton = panel.querySelector(".upload-activity-toggle");
        uploadState.toggleButton.addEventListener("click", () => {
            uploadState.minimized = !uploadState.minimized;
            renderUploadPanel();
        });
    };

    const updateUploadButtonState = () => {
        if (!uploadButton) {
            return;
        }

        const activeCount = activeUploads().length;
        uploadButton.classList.toggle("is-busy", activeCount > 0);
        uploadButton.title = activeCount > 0
            ? `Uploading ${activeCount} file${activeCount === 1 ? "" : "s"}`
            : uploadButton.dataset.readyTitle || "Upload files";
    };

    const removeUpload = (uploadId) => {
        const upload = uploadState.uploads.get(uploadId);
        upload?.row?.remove();
        uploadState.uploads.delete(uploadId);
        renderUploadPanel();
    };

    const scheduleUploadRemoval = (upload, delayMs) => {
        window.clearTimeout(upload.removeTimer);
        upload.removeTimer = window.setTimeout(() => removeUpload(upload.id), delayMs);
    };

    const cancelUpload = (upload) => {
        if (upload.status !== "uploading" || upload.cancelRequested) {
            return;
        }

        upload.cancelRequested = true;
        updateUploadRow(upload);
        upload.xhr?.abort();
    };

    const ensureUploadRow = (upload) => {
        if (upload.row) {
            return upload.row;
        }

        const item = document.createElement("article");
        item.className = "upload-item upload-uploading";
        item.innerHTML = `
            <div class="upload-item-main">
                <div class="upload-item-row">
                    <span class="upload-name"></span>
                    <span class="upload-percent">0%</span>
                </div>
                <div class="upload-meta"></div>
                <div class="upload-progress-track" role="progressbar"
                     aria-valuemin="0" aria-valuemax="100" aria-valuenow="0">
                    <div class="upload-progress-bar" style="width: 0%"></div>
                </div>
            </div>
        `;

        const cancelButton = document.createElement("button");
        cancelButton.className = "ghost icon-button upload-cancel";
        cancelButton.type = "button";
        cancelButton.title = "Cancel upload";
        cancelButton.setAttribute("aria-label", `Cancel ${upload.file.name}`);
        cancelButton.innerHTML = '<i class="fas fa-xmark" aria-hidden="true"></i>';
        cancelButton.addEventListener("click", () => cancelUpload(upload));
        item.append(cancelButton);

        upload.row = item;
        upload.nameElement = item.querySelector(".upload-name");
        upload.percentElement = item.querySelector(".upload-percent");
        upload.metaElement = item.querySelector(".upload-meta");
        upload.progressTrack = item.querySelector(".upload-progress-track");
        upload.progressBar = item.querySelector(".upload-progress-bar");
        upload.cancelButton = cancelButton;
        upload.nameElement.textContent = upload.file.name;
        return item;
    };

    const updateUploadRow = (upload) => {
        ensureUploadRow(upload);

        const percent = upload.status === "complete" ? 100 : uploadPercent(upload);
        upload.row.className = `upload-item upload-${upload.status}`;
        upload.percentElement.textContent = `${percent}%`;
        upload.metaElement.textContent = uploadStatusText(upload);
        upload.progressTrack.setAttribute("aria-valuenow", String(percent));
        upload.progressBar.style.width = `${percent}%`;

        const uploading = upload.status === "uploading";
        upload.cancelButton.hidden = !uploading;
        upload.cancelButton.disabled = upload.cancelRequested || !uploading;
        upload.cancelButton.title = upload.cancelRequested ? "Canceling upload" : "Cancel upload";
        upload.cancelButton.setAttribute(
                "aria-label",
                upload.cancelRequested ? `Canceling ${upload.file.name}` : `Cancel ${upload.file.name}`
        );
    };

    const renderUploadPanel = () => {
        ensureUploadPanel();

        const uploads = Array.from(uploadState.uploads.values());
        const activeCount = activeUploads().length;
        const doneCount = terminalUploads().length;
        if (uploads.length === 0) {
            uploadState.panel.hidden = true;
            updateUploadButtonState();
            return;
        }

        uploadState.panel.hidden = false;
        uploadState.panel.classList.toggle("is-minimized", uploadState.minimized);
        uploadState.summary.textContent = activeCount > 0
            ? `${activeCount} uploading - ${doneCount} finished`
            : `${doneCount} finished`;
        uploadState.toggleButton.title = uploadState.minimized ? "Show uploads" : "Minimize uploads";
        uploadState.toggleButton.setAttribute("aria-label", uploadState.toggleButton.title);
        uploadState.toggleButton.querySelector("i").className =
            uploadState.minimized ? "fas fa-chevron-up" : "fas fa-minus";

        uploads.forEach((upload) => {
            const row = ensureUploadRow(upload);
            if (row.parentElement !== uploadState.list) {
                uploadState.list.append(row);
            }
            updateUploadRow(upload);
        });
        updateUploadButtonState();
    };

    const uploadFormData = (file) => {
        const formData = new FormData();
        Array.from(uploadForm.elements).forEach((control) => {
            if (!control.name || control.disabled || control.type === "file") {
                return;
            }
            if ((control.type === "checkbox" || control.type === "radio") && !control.checked) {
                return;
            }
            formData.append(control.name, control.value);
        });
        formData.append(fileUploadInput.name || "files", file, file.name);
        return formData;
    };

    const finishUpload = (upload, status, message = "") => {
        if (upload.status !== "uploading") {
            return;
        }

        upload.status = status;
        upload.message = message;
        if (status === "complete") {
            upload.loaded = upload.total || upload.file.size;
            scheduleUploadRemoval(upload, 2600);
            requestListingRefresh(upload.redirectUrl || window.location.href);
        } else {
            scheduleUploadRemoval(upload, 6500);
        }
        renderUploadPanel();
    };

    const parseUploadResponse = (xhr) => {
        try {
            return JSON.parse(xhr.responseText || "{}");
        } catch (error) {
            return null;
        }
    };

    const startFileUploads = (files) => {
        if (!uploadForm || !fileUploadInput || !uploadButton || files.length === 0) {
            return;
        }

        ensureUploadPanel();
        files.forEach((file) => {
            const upload = {
                id: uploadState.nextId,
                file,
                loaded: 0,
                total: file.size,
                status: "uploading",
                message: "",
                xhr: null,
                redirectUrl: null,
                removeTimer: null,
                cancelRequested: false
            };
            uploadState.nextId += 1;
            uploadState.uploads.set(upload.id, upload);
            sendFileUpload(upload);
        });
        renderUploadPanel();
    };

    function sendFileUpload(upload) {
        const xhr = new XMLHttpRequest();
        upload.xhr = xhr;

        xhr.open((uploadForm.method || "POST").toUpperCase(), uploadForm.action);
        xhr.setRequestHeader("Accept", "application/json");
        xhr.setRequestHeader("X-Requested-With", "XMLHttpRequest");

        xhr.upload.onprogress = (event) => {
            upload.loaded = event.loaded;
            if (event.lengthComputable) {
                upload.total = event.total;
            }
            renderUploadPanel();
        };

        xhr.onload = () => {
            const body = parseUploadResponse(xhr);
            if (!body) {
                finishUpload(upload, "failed", "The server response could not be read.");
                return;
            }

            if (xhr.status >= 200 && xhr.status < 300 && body.ok !== false) {
                upload.redirectUrl = body.redirectUrl;
                finishUpload(upload, "complete");
                return;
            }

            const message = body.notification?.message || "Upload failed.";
            finishUpload(upload, "failed", message);
        };

        xhr.onerror = () => finishUpload(upload, "failed", "Upload failed.");
        xhr.onabort = () => finishUpload(upload, "canceled");

        xhr.send(uploadFormData(upload.file));
    }

    if (uploadForm && fileUploadInput && uploadButton && dropZone) {
        let dragDepth = 0;
        let isInternalDrag = false;
        const browserItemSelector = ".browser-card, .table-wrap a, .thumb-media, .video-thumb-wrap, .thumb-placeholder, .thumb-extension";

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

        const uploadDroppedFiles = (dataTransfer) => {
            const { files, hasDirectory } = collectDroppedFiles(dataTransfer);

            if (files.length === 0) {
                const message = hasDirectory
                    ? "Directory uploads are not supported yet."
                    : "Drop local files to upload.";
                showUploadStatus(message, true);
                return;
            }

            const suffix = hasDirectory ? " Directory items were skipped." : "";
            if (suffix) {
                showUploadStatus(suffix.trim(), true);
            }
            startFileUploads(files);
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

    document.addEventListener("pointerdown", (event) => {
        const item = selectableItemFromTarget(event.target);
        if (!item || event.button !== 0 || isSelectionControlTarget(event.target)) {
            return;
        }

        if (event.target.closest(".table-actions, .action-icon")) {
            return;
        }

        cancelLongPress();
        selectionState.longPressItem = item;
        selectionState.pointerId = event.pointerId;
        selectionState.pointerStartX = event.clientX;
        selectionState.pointerStartY = event.clientY;
        selectionState.longPressTimer = window.setTimeout(() => {
            selectionState.suppressNextClick = true;
            toggleItemSelection(item);
            cancelLongPress();
        }, longPressDelayMs);
    });

    document.addEventListener("pointermove", (event) => {
        if (!selectionState.longPressTimer || selectionState.pointerId !== event.pointerId) {
            return;
        }

        const moveX = Math.abs(event.clientX - selectionState.pointerStartX);
        const moveY = Math.abs(event.clientY - selectionState.pointerStartY);
        if (moveX > longPressMoveTolerance || moveY > longPressMoveTolerance) {
            cancelLongPress();
        }
    });

    document.addEventListener("pointerup", cancelLongPress);
    document.addEventListener("pointercancel", cancelLongPress);
    document.addEventListener("contextmenu", (event) => {
        if (selectionState.suppressNextClick && selectableItemFromTarget(event.target)) {
            event.preventDefault();
        }
    });

    document.addEventListener("click", (event) => {
        const item = selectableItemFromTarget(event.target);

        if (selectionState.suppressNextClick) {
            event.preventDefault();
            event.stopPropagation();
            selectionState.suppressNextClick = false;
            return;
        }

        if (item) {
            if (event.target.closest(".select-cell") && !event.target.matches('input[name="items"][form="bulkActionForm"]')) {
                event.preventDefault();
                toggleItemSelection(item);
                return;
            }

            if (isSelectionControlTarget(event.target)) {
                return;
            }

            const selectionClick = selectionState.active || event.ctrlKey || event.metaKey;
            if (selectionClick) {
                event.preventDefault();
                event.stopPropagation();
                toggleItemSelection(item);
                return;
            }

            if (event.target.closest("a[href]")) {
                return;
            }

            if (isNativeControlTarget(event.target)) {
                return;
            }

            const primaryLink = primaryLinkForItem(item);
            if (primaryLink) {
                event.preventDefault();
                window.location.href = primaryLink.href;
            }
            return;
        }

        if (selectionState.active && !event.target.closest(".toolbar, .upload-activity, .toast-region")) {
            exitSelectionMode();
        }
    });

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

    if (selectionButtons.length > 0 || selectAllCheckboxes().length > 0) {
        selectionButtons.forEach((button) => {
            button.dataset.readyTitle = button.title;
        });
        document.addEventListener("change", (event) => {
            if (event.target.matches("[data-select-all]")) {
                selectedItemCheckboxes().forEach((checkbox) => {
                    checkbox.checked = event.target.checked;
                });
                updateSelectionActions();
                return;
            }

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
