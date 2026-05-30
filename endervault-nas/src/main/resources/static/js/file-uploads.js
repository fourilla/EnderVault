document.addEventListener("DOMContentLoaded", () => {
    const uploadForm = document.getElementById("uploadForm");
    const fileUploadInput = document.getElementById("fileUploadInput");
    const uploadButton = document.getElementById("uploadButton");
    const dropZone = document.querySelector("[data-file-dropzone]");
    const dropUploadOverlay = document.getElementById("dropUploadOverlay");
    const uploadState = {
        uploads: new Map(),
        nextId: 1,
        minimized: false,
        panel: null,
        list: null,
        summary: null,
        toggleButton: null
    };

    if (!uploadForm || !fileUploadInput || !uploadButton) {
        return;
    }

    const showUploadStatus = (message, error = false) => {
        window.EnderVault.showToast(error ? "error" : "info", message);
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

    function updateUploadRow(upload) {
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
    }

    function renderUploadPanel() {
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
    }

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
            window.EnderVaultFileBrowser.requestListingRefresh(upload.redirectUrl || window.location.href);
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

    const startFileUploads = (files) => {
        if (files.length === 0) {
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

    uploadButton.dataset.readyTitle = uploadButton.title;
    uploadButton.addEventListener("click", () => fileUploadInput.click());
    fileUploadInput.addEventListener("change", () => {
        if (fileUploadInput.files.length > 0) {
            startFileUploads(Array.from(fileUploadInput.files));
            fileUploadInput.value = "";
        }
    });

    if (dropZone) {
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

    window.EnderVaultUploads = { startFileUploads };
});
