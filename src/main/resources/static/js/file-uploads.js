document.addEventListener("DOMContentLoaded", () => {
    const uploadForm = document.getElementById("uploadForm");
    const fileUploadInput = document.getElementById("fileUploadInput");
    const uploadButton = document.getElementById("uploadButton");
    const dropZone = document.querySelector("[data-file-dropzone]");
    const dropUploadOverlay = document.getElementById("dropUploadOverlay");
    const uploadState = {
        uploads: new Map(),
        nextId: 1,
        queue: [],
        running: 0,
        conflictQueue: [],
        conflictDialogOpen: false
    };

    if (!uploadForm || !fileUploadInput || !uploadButton) {
        return;
    }

    const maxFilesPerRequest = Math.max(0, Number.parseInt(uploadForm.dataset.maxFilesPerRequest || "0", 10) || 0);
    const maxConcurrentUploads = Math.max(
        1,
        Number.parseInt(uploadForm.dataset.maxConcurrentUploads || "1", 10) || 1
    );

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
        Array.from(uploadState.uploads.values()).filter((upload) =>
            ["queued", "fingerprinting", "reserving", "uploading", "canceling", "conflict", "resolving"]
                .includes(upload.status));

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
            case "queued":
                return "Waiting to upload";
            case "complete":
                return "Complete";
            case "failed":
                return upload.message || "Failed";
            case "canceled":
                return "Canceled";
            case "conflict":
                return "Waiting for conflict choice";
            case "resolving":
                return "Applying conflict choice...";
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
        if (!["queued", "fingerprinting", "reserving", "uploading"].includes(upload.status)
                || upload.cancelRequested) {
            return;
        }

        upload.cancelRequested = true;
        if (upload.status === "queued") {
            finishUpload(upload, "canceled");
            return;
        }
        upload.status = "canceling";
        renderUploadPanel();
        upload.handle?.abort()
            .then(() => finishUpload(upload, "canceled"))
            .catch((error) => finishUpload(upload, "failed", error.message || "Cancel failed."));
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
                percent: ["complete", "conflict", "resolving"].includes(upload.status) ? 100 : uploadPercent(upload),
                message: uploadStatusText(upload),
                cancelRequested: upload.cancelRequested,
                cancelable: ["queued", "fingerprinting", "reserving", "uploading"].includes(upload.status),
                onCancel: () => cancelUpload(upload)
            });
        });
        updateUploadButtonState();
    }

    const finishUpload = (upload, status, message = "") => {
        if (!["queued", "fingerprinting", "reserving", "uploading", "canceling", "conflict", "resolving"]
                .includes(upload.status)) {
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

    const conflictFormData = (conflict, policy) => {
        const formData = new FormData();
        const csrf = window.EnderVault.csrfPair(uploadForm);
        if (csrf) {
            formData.append(csrf.name, csrf.value);
        }
        formData.append("id", conflict.id);
        formData.append("conflictPolicy", policy);
        Array.from(uploadForm.elements).forEach((control) => {
            if (!control.name || control.disabled || control.type === "file" || control.name === "conflictPolicy") {
                return;
            }
            if ((control.type === "checkbox" || control.type === "radio") && !control.checked) {
                return;
            }
            if (!formData.has(control.name)) {
                formData.append(control.name, control.value);
            }
        });
        return formData;
    };

    const resolveUploadConflict = async (upload, policy) => {
        if (!upload.conflict || upload.status !== "conflict") {
            return;
        }

        upload.status = "resolving";
        renderUploadPanel();
        try {
            const body = await window.EnderVault.requestJson("/files/upload/conflicts/resolve", {
                method: "POST",
                body: conflictFormData(upload.conflict, policy)
            });
            upload.redirectUrl = body.redirectUrl;
            window.EnderVault.showNotification(body.notification);
            if (body.uploadedFile) {
                finishUpload(upload, "complete");
            } else {
                finishUpload(upload, "canceled");
            }
        } catch (error) {
            finishUpload(upload, "failed", error.message || "Conflict resolution failed.");
        }
    };

    const showNextConflictDialog = () => {
        if (uploadState.conflictDialogOpen || uploadState.conflictQueue.length === 0) {
            return;
        }

        const upload = uploadState.conflictQueue.shift();
        if (!upload || upload.status !== "conflict" || !upload.conflict) {
            showNextConflictDialog();
            return;
        }

        const defaultPolicy = upload.conflict.defaultPolicy || "cancel";
        const askPolicy = window.EnderVault.askFileConflictPolicy
            ? window.EnderVault.askFileConflictPolicy({
                ...upload.conflict,
                defaultPolicy,
                message: `"${upload.conflict.fileName}" already exists. Choose how to finish this upload.`
            })
            : Promise.resolve("default");

        uploadState.conflictDialogOpen = true;
        askPolicy.then((policy) => {
            uploadState.conflictDialogOpen = false;
            showNextConflictDialog();
            resolveUploadConflict(upload, policy);
        }).catch(() => {
            uploadState.conflictDialogOpen = false;
            showNextConflictDialog();
            resolveUploadConflict(upload, "default");
        });
    };

    const queueUploadConflict = (upload, conflict) => {
        upload.conflict = conflict;
        upload.status = "conflict";
        upload.loaded = upload.total || upload.file.size;
        uploadState.conflictQueue.push(upload);
        renderUploadPanel();
        showNextConflictDialog();
    };

    const validateUploadBatch = (files) => {
        if (!files || files.length === 0) {
            return false;
        }
        if (maxFilesPerRequest > 0 && files.length > maxFilesPerRequest) {
            showUploadStatus(
                `You can upload up to ${maxFilesPerRequest} file${maxFilesPerRequest === 1 ? "" : "s"} at once.`,
                true
            );
            return false;
        }
        return true;
    };

    async function sendFileUpload(upload) {
        const uploadClient = window.EnderVaultResumableUpload;
        if (!uploadClient) {
            finishUpload(upload, "failed", "Resumable upload support is unavailable.");
            return;
        }
        try {
            upload.handle = uploadClient.create({
                file: upload.file,
                context: uploadForm.dataset.admissionUrl,
                admissionUrl: uploadForm.dataset.admissionUrl,
                onState: (state, message) => {
                    upload.status = state;
                    upload.message = message;
                    renderUploadPanel();
                },
                onProgress: (sent, total) => {
                    upload.loaded = sent;
                    upload.total = total;
                    renderUploadPanel();
                }
            });
            const result = await upload.handle.start();
            if (!result || result.status === "CANCELED") {
                finishUpload(upload, "canceled");
                return;
            }
            if (result.status === "PENDING" && result.pendingDecisionId) {
                queueUploadConflict(upload, {
                    id: result.pendingDecisionId,
                    fileName: upload.file.name,
                    directoryPath: uploadForm.elements.namedItem("path")?.value || "",
                    defaultPolicy: result.defaultConflictPolicy || "cancel"
                });
                return;
            }
            if (result.status === "COMPLETED") {
                finishUpload(upload, "complete", result.message || "Upload complete");
                return;
            }
            finishUpload(upload, "failed", result.message || "Upload could not be finalized.");
        } catch (error) {
            finishUpload(upload, "failed", error.message || "Upload failed.");
        }
    }

    const startQueuedUploads = () => {
        while (uploadState.running < maxConcurrentUploads && uploadState.queue.length > 0) {
            const upload = uploadState.queue.shift();
            if (!upload || upload.status !== "queued") {
                continue;
            }
            uploadState.running += 1;
            sendFileUpload(upload).finally(() => {
                uploadState.running = Math.max(0, uploadState.running - 1);
                startQueuedUploads();
            });
        }
        renderUploadPanel();
    };

    const startFileUploads = (files) => {
        if (!validateUploadBatch(files)) {
            return;
        }

        files.forEach((file) => {
            const upload = {
                id: uploadState.nextId,
                file,
                loaded: 0,
                total: file.size,
                status: "queued",
                message: "",
                handle: null,
                redirectUrl: null,
                removeTimer: null,
                cancelRequested: false
            };
            uploadState.nextId += 1;
            uploadState.uploads.set(upload.id, upload);
            uploadState.queue.push(upload);
        });
        startQueuedUploads();
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
