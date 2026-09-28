import { mountDialog } from '../shared/dialogs/dialog-lifecycle';
import '../shared/dialogs/dialogs.css';
import { batchSummary, runBatch } from './upload-batch.js';

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
    let batch = null;
    let finished = false;
    let canceling = false;
    let unmountDialog = null;
    const dialog = document.querySelector('[data-upload-dialog]');
    if (!dialog) return;
    const find = (name) => dialog.querySelector(`[data-upload-${name}]`);
    const list = find('list');
    const pageRows = new Map();
    const dialogRows = new Map();

    const closeDialog = () => {
        if (running) return;
        unmountDialog?.();
        unmountDialog = null;
        if (finished) {
            for (let index = items.length - 1; index >= 0; index--) {
                if (items[index].state === 'complete') items.splice(index, 1);
            }
        }
        batch = null;
        finished = false;
        list.replaceChildren();
        dialogRows.clear();
        setStatus('');
        render();
    };

    const formatBytes = (bytes) => {
        if (bytes === 0) return "0 B";
        const units = ["B", "KB", "MB", "GB", "TB"];
        const index = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)));
        return `${(bytes / (1024 ** index)).toFixed(index === 0 ? 0 : 1)} ${units[index]}`;
    };

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
        if (item.state === 'queued') {
            item.state = 'canceled';
            item.message = 'Canceled';
            renderDialog();
            return;
        }
        if (!["fingerprinting", "reserving", "uploading"].includes(item.state)) {
            return;
        }
        item.state = "canceling";
        item.message = "Canceling";
        renderDialog();
        try {
            await item.handle.abort();
            // start() owns the final outcome: a concurrent server receipt still wins.
        } catch (error) {
            item.state = "failed";
            item.message = error.message || "Cancel failed";
        }
        renderDialog();
    };

    function updateRows(container, cache, entries, progressVisible) {
        const ids = new Set(entries.map(item => item.id));
        for (const [id, refs] of cache) {
            if (!ids.has(id)) { refs.row.remove(); cache.delete(id); }
        }
        for (const item of entries) {
            let refs = cache.get(item.id);
            if (!refs) {
                const row = document.createElement("div");
                const icon = document.createElement("i");
                icon.setAttribute("aria-hidden", "true");
                const details = document.createElement("span");
                const name = document.createElement("strong");
                name.textContent = item.file.name;
                name.title = item.file.name;
                const meta = document.createElement("small");
                details.append(name, meta);
                const progress = document.createElement('progress');
                progress.max = 100;
                progress.setAttribute('aria-label', `Transfer progress for ${item.file.name}`);
                details.append(progress);
                const remove = document.createElement('button');
                remove.className = 'ghost icon-button action-icon';
                remove.type = 'button';
                remove.innerHTML = '<i class="fas fa-xmark" aria-hidden="true"></i>';
                remove.addEventListener('click', () => {
                    if (progressVisible) cancellations.push(cancelItem(item));
                    else if (!batch) removeItem(item);
                });
                row.append(icon, details, remove);
                refs = { row, icon, meta, progress, remove };
                cache.set(item.id, refs);
                container.append(row);
            }
            refs.row.className = `file-request-queue-item is-${item.state}`;
            refs.icon.className = item.state === 'complete' ? 'fas fa-circle-check'
                : item.state === 'failed' ? 'fas fa-circle-exclamation' : 'fas fa-file';
            const percent = item.file.size ? Math.min(100, Math.floor(item.loaded * 100 / item.file.size)) : item.state === 'complete' ? 100 : 0;
            const message = progressVisible && item.state === 'uploading' && item.loaded >= item.file.size
                ? 'Confirming receipt' : item.message;
            refs.meta.textContent = `${formatBytes(item.file.size)}${message ? ` / ${message}` : ''}${progressVisible && item.state === 'uploading' ? ` / ${percent}%` : ''}`;
            refs.meta.title = refs.meta.textContent;
            refs.progress.hidden = !progressVisible || (!running && !finished);
            refs.progress.value = percent;
            refs.remove.hidden = progressVisible && (!running || !['queued', 'fingerprinting', 'reserving', 'uploading'].includes(item.state));
            refs.remove.disabled = progressVisible ? canceling : Boolean(batch);
            refs.remove.title = progressVisible ? 'Cancel upload' : 'Remove';
            refs.remove.setAttribute('aria-label', `${refs.remove.title} ${item.file.name}`);
        }
    }

    function render() {
        queue.hidden = items.length === 0;
        updateRows(queue, pageRows, items, false);
        submit.disabled = Boolean(batch) || items.length === 0;
        picker.disabled = Boolean(batch);
        if (uploaderName) uploaderName.disabled = Boolean(batch);
    }

    function renderDialog() {
        if (!batch) return;
        const summary = batchSummary(batch);
        find('title').textContent = finished ? 'Upload results' : running ? 'Uploading files' : 'Confirm upload';
        find('summary').textContent = `${batch.length} file(s) / ${formatBytes(summary.total)}`;
        find('progress-area').hidden = !running && !finished;
        find('progress').value = summary.percent;
        find('totals').textContent = `${Math.floor(summary.percent)}% / ${formatBytes(summary.loaded)} of ${formatBytes(summary.total)} transferred`;
        find('result').textContent = running ? canceling ? 'Canceling uploads...' : `${summary.complete} received / ${summary.failed} failed`
            : finished ? `${summary.complete} received / ${summary.failed} failed / ${summary.canceled} canceled` : '';
        find('close').disabled = running;
        find('done').hidden = running;
        find('start').hidden = running || finished;
        find('cancel').hidden = !running;
        find('cancel').disabled = canceling;
        updateRows(list, dialogRows, batch, true);
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
        if (batch) return;
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
        try {
            item.handle = uploadClient.create({
                file: item.file,
                context: form.dataset.admissionUrl,
                admissionUrl: form.dataset.admissionUrl,
                uploaderName: uploaderName?.value || null,
                onState: (state, message) => {
                    if (item.state === 'canceling') return;
                    item.state = state;
                    item.message = message;
                    renderDialog();
                },
                onProgress: (sent) => {
                    item.loaded = sent;
                    renderDialog();
                }
            });
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
        renderDialog();
    };

    let cancellations = [];
    find('close').addEventListener('click', closeDialog);
    find('done').addEventListener('click', closeDialog);
    find('cancel').addEventListener('click', () => {
        if (!running || canceling) return;
        canceling = true;
        cancellations.push(...batch.map(cancelItem));
        renderDialog();
    });
    find('start').addEventListener('click', async () => {
        if (!batch || running || finished) return;
        running = true;
        canceling = false;
        cancellations = [];
        for (const item of batch) {
            item.state = 'queued'; item.message = 'Ready'; item.loaded = 0; item.handle = null;
        }
        renderDialog();
        await runBatch(batch, parallelUploads, processItem);
        await Promise.allSettled(cancellations);
        running = false;
        finished = true;
        renderDialog();
    });

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
        if (batch) return;
        const droppedFiles = Array.from(event.dataTransfer?.files || []);
        if (await containsDirectory(event.dataTransfer, droppedFiles)) {
            rejectDirectories();
            return;
        }
        addFiles(droppedFiles);
    });
    form.addEventListener("submit", (event) => {
        event.preventDefault();
        if (batch || !items.length) return;
        if (uploaderName && !uploaderName.reportValidity()) return;
        batch = items.slice();
        finished = false;
        render();
        renderDialog();
        unmountDialog = mountDialog(dialog, () => ({
            busy: running, dismissOnBackdrop: false, dismissOnEscape: true, onDismiss: closeDialog,
        }));
    });
    window.addEventListener("beforeunload", (event) => {
        if (!running) {
            return;
        }
        event.preventDefault();
        event.returnValue = "";
    });
})();
