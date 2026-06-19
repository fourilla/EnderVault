document.addEventListener("DOMContentLoaded", () => {
    const uploadForm = document.getElementById("uploadForm");
    const fileUploadInput = document.getElementById("fileUploadInput");
    const uploadButton = document.getElementById("uploadButton");
    const dropZone = document.querySelector("[data-file-dropzone]");
    const dropUploadOverlay = document.getElementById("dropUploadOverlay");
    const uploadState = {
        uploads: new Map(),
        nextId: 1
    };

    if (!uploadForm || !fileUploadInput || !uploadButton) {
        return;
    }

    const showUploadStatus = (message, error = false) => {
        window.EnderVault.showToast(error ? "error" : "info", message);
    };

    const formatBytes = (bytes) => {
        if (window.EnderVaultActivity?.formatBytes) {
            return window.EnderVaultActivity.formatBytes(bytes);
        }
        return `${bytes || 0} B`;
    };

    const activeUploads = () =>
        Array.from(uploadState.uploads.values()).filter((upload) => upload.status === "uploading");

    window.addEventListener("beforeunload", (event) => {
        if (activeUploads().length === 0) {
            return;
        }
        event.preventDefault();
        event.returnValue = "";
    });

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

    const updateUploadButtonState = () => {
        const activeCount = activeUploads().length;
        uploadButton.classList.toggle("is-busy", activeCount > 0);
        uploadButton.title = activeCount > 0
            ? `Uploading ${activeCount} file${activeCount === 1 ? "" : "s"}`
            : uploadButton.dataset.readyTitle || "Upload files";
    };

    const removeUpload = (uploadId) => {
        window.EnderVaultActivity?.remove(`upload-${uploadId}`);
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
        renderUploadPanel();
        upload.xhr?.abort();
    };

    function renderUploadPanel() {
        const uploads = Array.from(uploadState.uploads.values());
        if (uploads.length === 0) {
            updateUploadButtonState();
            return;
        }

        uploads.forEach((upload) => {
            window.EnderVaultActivity?.upsert({
                id: `upload-${upload.id}`,
                title: upload.file.name,
                type: "UPLOAD",
                typeLabel: "Upload",
                status: upload.status,
                percent: upload.status === "complete" ? 100 : uploadPercent(upload),
                message: uploadStatusText(upload),
                cancelRequested: upload.cancelRequested,
                cancelable: upload.status === "uploading",
                onCancel: () => cancelUpload(upload)
            });
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
