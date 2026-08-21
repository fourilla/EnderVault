(() => {
    const form = document.querySelector("[data-file-request-upload]");
    if (!form) {
        return;
    }

    const input = form.querySelector("[data-file-request-input]");
    const picker = form.querySelector("[data-file-request-pick]");
    const submit = form.querySelector("[data-file-request-submit]");
    const queue = form.querySelector("[data-file-request-queue]");
    const status = form.querySelector("[data-file-request-status]");
    const uploaderName = form.elements.namedItem("uploaderName");
    const uploadClient = window.EnderVaultResumableUpload;
    if (!input || !picker || !submit || !queue || !status || !uploadClient) {
        return;
    }

    const parallelUploads = Math.max(1, Number.parseInt(form.dataset.parallelUploads || "2", 10));
    const acceptedExtensions = input.accept.split(",")
        .map(extension => extension.trim().toLowerCase())
        .filter(extension => extension.startsWith(".") && extension.length > 1);
    const items = [];
    let nextId = 1;
    let running = false;

    const formatBytes = (bytes) => {
        if (bytes === 0) return "0 B";
        const units = ["B", "KB", "MB", "GB", "TB"];
        const index = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)));
        return `${(bytes / (1024 ** index)).toFixed(index === 0 ? 0 : 1)} ${units[index]}`;
    };

    const activeItems = () => items.filter(item =>
        ["fingerprinting", "reserving", "uploading", "canceling"].includes(item.state));

    const setStatus = (message, failed = false) => {
        status.textContent = message;
        status.classList.toggle("is-error", failed);
    };

    const removeItem = (item) => {
        const index = items.indexOf(item);
        if (index >= 0) {
            items.splice(index, 1);
        }
        render();
    };

    const cancelItem = async (item) => {
        if (!item.handle || item.state === "queued") {
            removeItem(item);
            return;
        }
        if (!["fingerprinting", "reserving", "uploading"].includes(item.state)) {
            return;
        }
        item.state = "canceling";
        item.message = "Canceling";
        render();
        try {
            await item.handle.abort();
            item.state = "canceled";
            item.message = "Canceled";
        } catch (error) {
            item.state = "failed";
            item.message = error.message || "Cancel failed";
        }
        render();
    };

    function render() {
        queue.hidden = items.length === 0;
        queue.replaceChildren(...items.map((item) => {
            const row = document.createElement("div");
            row.className = `file-request-queue-item is-${item.state}`;

            const icon = document.createElement("i");
            icon.className = item.state === "complete" || item.state === "pending"
                ? "fas fa-circle-check"
                : item.state === "failed"
                    ? "fas fa-circle-exclamation"
                    : "fas fa-file";
            icon.setAttribute("aria-hidden", "true");

            const details = document.createElement("span");
            const name = document.createElement("strong");
            name.textContent = item.file.name;
            const meta = document.createElement("small");
            const progress = item.state === "uploading" && item.file.size > 0
                ? ` / ${Math.floor(item.loaded * 100 / item.file.size)}%`
                : "";
            meta.textContent = `${formatBytes(item.file.size)} / ${item.message || item.state}${progress}`;
            details.append(name, meta);

            row.append(icon, details);
            const active = ["queued", "fingerprinting", "reserving", "uploading"].includes(item.state);
            const dismissible = ["complete", "pending", "failed", "canceled"].includes(item.state);
            if (active || dismissible) {
                const remove = document.createElement("button");
                remove.className = "ghost icon-button action-icon";
                remove.type = "button";
                remove.title = item.state === "queued" || dismissible ? "Remove" : "Cancel upload";
                remove.setAttribute("aria-label", `${remove.title} ${item.file.name}`);
                remove.innerHTML = '<i class="fas fa-xmark" aria-hidden="true"></i>';
                remove.addEventListener("click", () => dismissible ? removeItem(item) : cancelItem(item));
                row.append(remove);
            }
            return row;
        }));
        submit.disabled = running || !items.some(item => item.state === "queued");
    }

    const containsRelativePaths = (files) => Array.from(files || [])
        .some(file => Boolean(file.webkitRelativePath));

    const containsDirectory = async (dataTransfer, droppedFiles) => {
        const itemsToInspect = Array.from(dataTransfer?.items || []);
        const handleRequests = [];
        for (const item of itemsToInspect) {
            if (item.kind !== "file") {
                continue;
            }
            const entry = item.webkitGetAsEntry?.();
            if (entry) {
                if (entry.isDirectory) {
                    return true;
                }
                continue;
            }
            if (typeof item.getAsFileSystemHandle === "function") {
                handleRequests.push(item.getAsFileSystemHandle().catch(() => null));
            }
        }
        const handles = await Promise.all(handleRequests);
        return handles.some(handle => handle?.kind === "directory") || containsRelativePaths(droppedFiles);
    };

    const rejectDirectories = () => {
        setStatus("Directory uploads are not supported yet. Select individual files instead.", true);
        render();
    };

    const acceptsFile = (file) => {
        if (acceptedExtensions.length === 0) {
            return true;
        }
        const filename = file.name.toLowerCase();
        return acceptedExtensions.some(extension =>
            filename.length > extension.length && filename.endsWith(extension));
    };

    const sameLocalFile = (left, right) => left.name === right.name
        && left.size === right.size
        && left.lastModified === right.lastModified
        && left.type === right.type;

    const addFiles = (files) => {
        if (containsRelativePaths(files)) {
            rejectDirectories();
            return;
        }
        const selected = Array.from(files || []);
        const accepted = selected.filter(acceptsFile);
        const rejected = selected.length - accepted.length;
        accepted.forEach((file) => {
            const failed = items.find(item => item.state === "failed" && sameLocalFile(item.file, file));
            if (failed) {
                failed.file = file;
                failed.state = "queued";
                failed.loaded = 0;
                failed.message = "Ready";
                failed.handle = null;
                return;
            }
            items.push({
                id: nextId++,
                file,
                state: "queued",
                loaded: 0,
                message: "Ready",
                handle: null
            });
        });
        const selectedMessage = accepted.length > 0 ? `${accepted.length} file(s) selected.` : "";
        const rejectedMessage = rejected > 0
            ? `${rejected} file(s) skipped because their extensions are not accepted.`
            : "";
        setStatus([selectedMessage, rejectedMessage].filter(Boolean).join(" "), rejected > 0);
        render();
    };

    const processItem = async (item) => {
        item.handle = uploadClient.create({
            file: item.file,
            context: form.dataset.admissionUrl,
            admissionUrl: form.dataset.admissionUrl,
            uploaderName: uploaderName?.value || null,
            onState: (state, message) => {
                item.state = state;
                item.message = message;
                render();
            },
            onProgress: (sent) => {
                item.loaded = sent;
                render();
            }
        });
        try {
            const result = await item.handle.start();
            if (!result || result.status === "CANCELED") {
                item.state = "canceled";
                item.message = "Canceled";
            } else if (result.status === "RECEIVED") {
                item.state = "complete";
                item.loaded = item.file.size;
                item.message = result.message || "Upload received";
            } else {
                item.state = "failed";
                item.message = result.message || "Upload could not be finalized";
            }
        } catch (error) {
            item.state = "failed";
            item.message = error.message || "Upload failed";
        }
        render();
    };

    const runQueue = async () => {
        const pending = items.filter(item => item.state === "queued");
        let cursor = 0;
        const worker = async () => {
            while (cursor < pending.length) {
                const item = pending[cursor++];
                await processItem(item);
            }
        };
        await Promise.all(Array.from({ length: Math.min(parallelUploads, pending.length) }, worker));
        return pending;
    };

    picker.addEventListener("click", () => input.click());
    input.addEventListener("change", () => {
        addFiles(input.files);
        input.value = "";
    });
    ["dragenter", "dragover"].forEach(type => picker.addEventListener(type, (event) => {
        event.preventDefault();
        picker.classList.add("is-dragging");
    }));
    ["dragleave", "drop"].forEach(type => picker.addEventListener(type, (event) => {
        event.preventDefault();
        picker.classList.remove("is-dragging");
    }));
    picker.addEventListener("drop", async (event) => {
        const droppedFiles = Array.from(event.dataTransfer?.files || []);
        if (await containsDirectory(event.dataTransfer, droppedFiles)) {
            rejectDirectories();
            return;
        }
        addFiles(droppedFiles);
    });
    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        if (running || !items.some(item => item.state === "queued")) return;
        if (uploaderName && !uploaderName.reportValidity()) return;
        running = true;
        render();
        setStatus("Uploads are in progress.");
        const attempted = await runQueue();
        running = false;
        const failed = attempted.filter(item => item.state === "failed").length;
        const message = failed
            ? `${failed} upload(s) failed. Review the message shown for each file.`
            : "All uploads were received.";
        setStatus(message, failed > 0);
        render();
    });
    window.addEventListener("beforeunload", (event) => {
        if (activeItems().length === 0) {
            return;
        }
        event.preventDefault();
        event.returnValue = "";
    });
})();
