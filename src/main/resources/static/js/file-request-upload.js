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
    if (!input || !picker || !submit || !queue || !status) {
        return;
    }
    const csrfToken = document.querySelector('meta[name="_csrf"]')?.content || "";
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || "X-CSRF-TOKEN";
    const parallelUploads = Math.max(1, Number.parseInt(form.dataset.parallelUploads || "2", 10));
    const items = [];
    let nextId = 1;
    let running = false;

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

    const render = () => {
        queue.hidden = items.length === 0;
        queue.replaceChildren(...items.map((item) => {
            const row = document.createElement("div");
            row.className = `file-request-queue-item is-${item.state}`;

            const icon = document.createElement("i");
            icon.className = item.state === "complete"
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
            if (item.state === "queued") {
                const remove = document.createElement("button");
                remove.className = "ghost icon-button action-icon";
                remove.type = "button";
                remove.title = "Remove";
                remove.setAttribute("aria-label", `Remove ${item.file.name}`);
                remove.innerHTML = '<i class="fas fa-xmark" aria-hidden="true"></i>';
                remove.addEventListener("click", () => {
                    const index = items.findIndex(candidate => candidate.id === item.id);
                    if (index >= 0) items.splice(index, 1);
                    render();
                });
                row.append(remove);
            }
            return row;
        }));
        submit.disabled = running || !items.some(item => item.state === "queued");
    };

    const containsRelativePaths = (files) => Array.from(files || [])
        .some(file => Boolean(file.webkitRelativePath));

    const containsDirectory = async (dataTransfer, droppedFiles) => {
        const items = Array.from(dataTransfer?.items || []);
        const handleRequests = [];
        for (const item of items) {
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
        return handles.some(handle => handle?.kind === "directory")
            || containsRelativePaths(droppedFiles);
    };

    const rejectDirectories = () => {
        setStatus("Directory uploads are not supported yet. Select individual files instead.", true);
        render();
    };

    const addFiles = (files) => {
        if (containsRelativePaths(files)) {
            rejectDirectories();
            return;
        }
        Array.from(files || []).forEach((file) => {
            items.push({ id: nextId++, file, state: "queued", loaded: 0, message: "Ready" });
        });
        setStatus(items.length ? `${items.length} file(s) selected.` : "");
        render();
    };

    const parseResponse = async (response) => {
        try {
            return await response.json();
        } catch (error) {
            return { ok: false, message: "The server response could not be read." };
        }
    };

    const issueTicket = async (item) => {
        const response = await fetch(form.dataset.ticketUrl, {
            method: "POST",
            credentials: "same-origin",
            headers: {
                "Accept": "application/json",
                "Content-Type": "application/json",
                [csrfHeader]: csrfToken
            },
            body: JSON.stringify({
                filename: item.file.name,
                size: item.file.size,
                uploaderName: uploaderName?.value || null
            })
        });
        const body = await parseResponse(response);
        if (!response.ok || body.ok === false) {
            throw new Error(body.message || "Upload could not be reserved.");
        }
        return body.ticketId;
    };

    const uploadOnce = (item, ticketId) => new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        const url = `${form.dataset.uploadBase}${encodeURIComponent(ticketId)}`;
        xhr.open("PUT", url);
        xhr.setRequestHeader("Accept", "application/json");
        xhr.setRequestHeader("Content-Type", "application/octet-stream");
        xhr.setRequestHeader(csrfHeader, csrfToken);
        xhr.upload.onprogress = (event) => {
            item.loaded = event.loaded;
            render();
        };
        xhr.onload = () => {
            let body;
            try {
                body = JSON.parse(xhr.responseText || "{}");
            } catch (error) {
                body = { ok: false, message: "The server response could not be read." };
            }
            if (xhr.status >= 200 && xhr.status < 300 && body.ok !== false) {
                resolve(body);
                return;
            }
            const failure = new Error(body.message || "Upload failed.");
            failure.retryAfterSeconds = body.retryAfterSeconds;
            failure.status = xhr.status;
            reject(failure);
        };
        xhr.onerror = () => reject(new Error("Upload connection failed."));
        xhr.send(item.file);
    });

    const uploadWithRetry = async (item, ticketId) => {
        for (let attempt = 0; attempt < 10; attempt += 1) {
            try {
                return await uploadOnce(item, ticketId);
            } catch (error) {
                if (error.status !== 429 || attempt === 9) {
                    throw error;
                }
                item.message = "Waiting for upload capacity";
                render();
                const delay = Math.max(1, Number(error.retryAfterSeconds) || 2) * 1000;
                await new Promise(resolve => window.setTimeout(resolve, delay));
            }
        }
        throw new Error("Upload capacity remained unavailable.");
    };

    const processItem = async (item) => {
        item.state = "reserving";
        item.message = "Reserving";
        render();
        try {
            const ticketId = await issueTicket(item);
            item.state = "uploading";
            item.message = "Uploading";
            render();
            const result = await uploadWithRetry(item, ticketId);
            item.state = "complete";
            item.loaded = item.file.size;
            item.message = result.message || "Upload received";
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
        // DataTransfer is only guaranteed to expose files during the drop event.
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
        await runQueue();
        running = false;
        const failed = items.filter(item => item.state === "failed").length;
        setStatus(failed ? `${failed} upload(s) failed. You can retry them by selecting the files again.` : "All uploads were received.", failed > 0);
        render();
    });
})();
