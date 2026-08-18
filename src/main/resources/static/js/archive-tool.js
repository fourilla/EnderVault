document.addEventListener("DOMContentLoaded", () => {
    const tool = document.querySelector("[data-archive-tool]");
    const tree = tool?.querySelector("[data-archive-tree]");
    const status = tool?.querySelector("[data-archive-status]");
    const form = tool?.querySelector("[data-archive-extract-form]");
    const submitButton = form?.querySelector("[data-archive-extract-submit]");
    const containerToggle = form?.querySelector("[data-archive-container-toggle]");
    const containerName = form?.querySelector("[data-archive-container-name]");
    const containerNameField = form?.querySelector("[data-archive-container-name-field]");
    const layoutHint = form?.querySelector("[data-archive-layout-hint]");
    const { requestJson, submitJsonForm, showNotification } = window.EnderVault || {};
    if (!tool || !tree || !requestJson) {
        return;
    }
    let archiveExtractable = false;
    let archiveStatusMessage = "Archive information is still loading.";
    let layoutTouched = false;
    if (submitButton) {
        submitButton.disabled = true;
    }

    const updateContainerControls = () => {
        const createContainer = containerToggle?.checked !== false;
        if (containerName) {
            containerName.disabled = !createContainer;
        }
        containerNameField?.classList.toggle("is-disabled", !createContainer);
        containerNameField?.setAttribute("aria-disabled", String(!createContainer));
    };

    containerToggle?.addEventListener("change", () => {
        layoutTouched = true;
        updateContainerControls();
        if (layoutHint) {
            layoutHint.textContent = containerToggle.checked
                ? "Archive contents will be placed inside this directory."
                : "Top-level archive items will be placed directly in the destination.";
        }
    });
    updateContainerControls();

    const entriesUrl = (parent = "") => {
        const url = new URL(tool.dataset.entriesUrl, window.location.origin);
        if (parent) {
            url.searchParams.set("parent", parent);
        }
        return url.toString();
    };

    const updateSummary = (payload) => {
        archiveExtractable = payload.extractable;
        archiveStatusMessage = payload.message || "Encrypted or unsupported archive extraction is not supported.";
        tool.querySelector("[data-archive-format]").textContent = payload.format;
        tool.querySelector("[data-archive-files]").textContent = payload.browsable ? payload.fileCount : "Unknown";
        tool.querySelector("[data-archive-directories]").textContent = payload.browsable
            ? payload.directoryCount
            : "Unknown";
        tool.querySelector("[data-archive-size]").textContent = payload.browsable ? payload.totalSizeLabel : "Unknown";
        if (!payload.extractable) {
            status.textContent = payload.message || "This archive cannot be extracted safely.";
            status.classList.add("is-warning");
        }
        if (submitButton) {
            submitButton.disabled = false;
            submitButton.classList.toggle("is-unavailable", !archiveExtractable);
            submitButton.setAttribute("aria-disabled", String(!archiveExtractable));
            submitButton.title = archiveExtractable ? "Extract archive" : archiveStatusMessage;
        }
    };

    const createList = () => {
        const list = document.createElement("ul");
        list.className = "archive-tree-list";
        list.setAttribute("role", "group");
        return list;
    };

    const createUnavailableTreeMessage = () => {
        const message = document.createElement("p");
        message.className = "archive-tree-message muted";
        message.textContent = "Archive contents cannot be browsed because its entry metadata is unavailable.";
        return message;
    };

    const createNode = (entry) => {
        const node = document.createElement("li");
        node.className = "archive-tree-node";
        node.dataset.path = entry.path;
        node.setAttribute("role", "treeitem");

        const row = document.createElement("div");
        row.className = "archive-tree-row";
        row.title = entry.path;

        let toggle;
        if (entry.directory) {
            toggle = document.createElement("button");
            toggle.type = "button";
            toggle.className = "archive-tree-toggle";
            toggle.title = `Expand ${entry.name}`;
            toggle.setAttribute("aria-label", toggle.title);
            toggle.innerHTML = '<i class="fas fa-chevron-right" aria-hidden="true"></i>';
            node.setAttribute("aria-expanded", "false");
        } else {
            toggle = document.createElement("span");
            toggle.className = "archive-tree-spacer";
        }

        const icon = document.createElement("i");
        icon.className = `archive-tree-icon fas ${entry.directory ? "fa-folder" : "fa-file"}`;
        icon.setAttribute("aria-hidden", "true");

        const name = document.createElement("span");
        name.className = "archive-tree-name";
        name.textContent = entry.name;

        const size = document.createElement("span");
        size.className = "archive-tree-size";
        size.textContent = entry.sizeLabel;

        row.append(toggle, icon, name, size);
        node.append(row);

        if (entry.directory) {
            const children = createList();
            children.hidden = true;
            node.append(children);
            const toggleDirectory = async () => {
                if (node.dataset.loading === "true") {
                    return;
                }
                const expanded = node.classList.contains("expanded");
                if (expanded) {
                    node.classList.remove("expanded");
                    node.setAttribute("aria-expanded", "false");
                    children.hidden = true;
                    return;
                }
                if (node.dataset.loaded !== "true") {
                    node.dataset.loading = "true";
                    node.setAttribute("aria-busy", "true");
                    try {
                        const payload = await requestJson(entriesUrl(entry.path));
                        payload.entries.forEach((child) => children.append(createNode(child)));
                        node.dataset.loaded = "true";
                        if (payload.entries.length === 0) {
                            toggle.hidden = true;
                        }
                    } finally {
                        delete node.dataset.loading;
                        node.removeAttribute("aria-busy");
                    }
                }
                node.classList.add("expanded");
                node.setAttribute("aria-expanded", "true");
                children.hidden = false;
            };
            toggle.addEventListener("click", () => toggleDirectory().catch(showTreeError));
            row.addEventListener("dblclick", (event) => {
                event.preventDefault();
                toggleDirectory().catch(showTreeError);
            });
        }
        return node;
    };

    const showTreeError = (error) => {
        archiveExtractable = false;
        archiveStatusMessage = error.message || "Archive entries could not be loaded.";
        if (submitButton) {
            submitButton.disabled = false;
            submitButton.classList.add("is-unavailable");
            submitButton.setAttribute("aria-disabled", "true");
            submitButton.title = archiveStatusMessage;
        }
        status.hidden = false;
        status.textContent = archiveStatusMessage;
        status.classList.add("is-warning");
    };

    const showUnavailableExtraction = () => {
        showNotification?.({ type: "warning", message: archiveStatusMessage });
    };

    submitButton?.addEventListener("click", (event) => {
        if (!archiveExtractable && !submitButton.disabled) {
            event.preventDefault();
            showUnavailableExtraction();
        }
    });

    requestJson(entriesUrl())
        .then((payload) => {
            updateSummary(payload);
            if (containerToggle && !layoutTouched) {
                const topLevelCount = payload.entries.length;
                containerToggle.checked = !payload.browsable || topLevelCount !== 1;
                updateContainerControls();
                if (layoutHint) {
                    if (!payload.browsable) {
                        layoutHint.textContent = "Archive layout is unavailable because its entry metadata cannot be read.";
                    } else if (!payload.extractable && topLevelCount === 0) {
                        layoutHint.textContent = "No safe top-level items are available for extraction.";
                    } else {
                        layoutHint.textContent = topLevelCount === 1
                            ? "One top-level item detected. Direct extraction is recommended."
                            : topLevelCount === 0
                                ? "The archive is empty, so a containing directory is recommended."
                                : `${topLevelCount} top-level items detected. A containing directory is recommended.`;
                    }
                }
            }
            if (!payload.browsable) {
                tree.replaceChildren(createUnavailableTreeMessage());
            } else {
                const list = createList();
                list.setAttribute("role", "tree");
                list.setAttribute("aria-label", "Archive contents");
                payload.entries.forEach((entry) => list.append(createNode(entry)));
                tree.replaceChildren(list);
            }
            if (payload.extractable && payload.entries.length === 0) {
                status.textContent = "This archive is empty.";
            } else if (payload.extractable) {
                status.hidden = true;
            }
        })
        .catch(showTreeError);

    form?.addEventListener("submit", async (event) => {
        if (!archiveExtractable) {
            event.preventDefault();
            showUnavailableExtraction();
            return;
        }
        if (!submitJsonForm) {
            return;
        }
        event.preventDefault();
        submitButton.disabled = true;
        try {
            const payload = await submitJsonForm(form);
            showNotification?.(payload.notification);
            if (payload.task) {
                window.EnderVaultServerTasks?.track(payload.task);
            }
        } catch (error) {
            showNotification?.({ type: "error", message: error.message || "Archive extraction could not be queued." });
        } finally {
            submitButton.disabled = false;
        }
    });
});
