document.addEventListener("DOMContentLoaded", () => {
    const dialog = document.querySelector("[data-storage-directory-picker]");
    const tree = dialog?.querySelector("[data-directory-picker-tree]");
    const selectionLabel = dialog?.querySelector("[data-directory-picker-selection]");
    const { requestJson, showNotification } = window.EnderVault || {};
    if (!dialog || !tree || !requestJson) {
        return;
    }

    let targetInput = null;
    let selectedPath = "";

    const normalizedPath = (value) => String(value ?? "")
            .trim()
            .replaceAll("\\", "/")
            .replace(/^\/+|\/+$/g, "");

    const updateSelection = (path, button) => {
        selectedPath = normalizedPath(path);
        tree.querySelectorAll(".directory-picker-select.selected").forEach((item) => {
            item.classList.remove("selected");
        });
        button?.classList.add("selected");
        if (selectionLabel) {
            selectionLabel.textContent = selectedPath ? `/${selectedPath}` : "/";
        }
    };

    const entriesUrl = (path) => {
        const url = new URL(dialog.dataset.entriesUrl, window.location.origin);
        url.searchParams.set("path", normalizedPath(path));
        url.searchParams.set("types", "directory");
        return url.toString();
    };

    const childList = () => {
        const list = document.createElement("ul");
        list.setAttribute("role", "group");
        list.hidden = true;
        return list;
    };

    const nodeFor = (entry) => {
        const node = document.createElement("li");
        node.className = "directory-picker-node";
        node.dataset.path = entry.path;
        node.setAttribute("role", "treeitem");
        node.setAttribute("aria-expanded", "false");

        const row = document.createElement("div");
        row.className = "directory-picker-node-row";

        const expand = document.createElement("button");
        expand.className = "ghost icon-button directory-picker-expand";
        expand.type = "button";
        expand.title = "Expand directory";
        expand.setAttribute("aria-label", `Expand ${entry.name}`);
        expand.innerHTML = '<i class="fas fa-chevron-right" aria-hidden="true"></i>';

        const select = document.createElement("button");
        select.className = "ghost icon-text-button directory-picker-select";
        select.type = "button";
        select.title = entry.path ? `/${entry.path}` : "/";
        select.innerHTML = '<i class="fas fa-folder" aria-hidden="true"></i>';
        const label = document.createElement("span");
        label.textContent = entry.name;
        select.append(label);
        select.addEventListener("click", () => updateSelection(entry.path, select));

        const children = childList();
        expand.addEventListener("click", () => toggleNode(node, children));
        row.append(expand, select);
        node.append(row, children);
        return node;
    };

    const loadChildren = async (node, children) => {
        if (node.dataset.loaded === "true") {
            return;
        }
        node.dataset.loading = "true";
        try {
            const payload = await requestJson(entriesUrl(node.dataset.path));
            payload.entries.forEach((entry) => children.append(nodeFor(entry)));
            node.dataset.loaded = "true";
            if (payload.entries.length === 0) {
                node.classList.add("empty");
            }
        } finally {
            delete node.dataset.loading;
        }
    };

    async function toggleNode(node, children, expandOnly = false) {
        if (node.dataset.loading === "true") {
            return;
        }
        const expanded = node.classList.contains("expanded");
        if (expanded && !expandOnly) {
            node.classList.remove("expanded");
            node.setAttribute("aria-expanded", "false");
            children.hidden = true;
            return;
        }
        try {
            await loadChildren(node, children);
            node.classList.add("expanded");
            node.setAttribute("aria-expanded", "true");
            children.hidden = false;
        } catch (error) {
            showNotification?.({ type: "error", message: error.message || "Directories could not be loaded." });
        }
    }

    const revealPath = async (path) => {
        const segments = normalizedPath(path).split("/").filter(Boolean);
        let node = tree.querySelector('.directory-picker-node[data-path=""]');
        if (!node) {
            return;
        }
        await toggleNode(node, node.lastElementChild, true);
        let currentPath = "";
        for (const segment of segments) {
            currentPath = currentPath ? `${currentPath}/${segment}` : segment;
            const child = Array.from(node.lastElementChild.children)
                    .find((item) => item.dataset.path === currentPath);
            if (!child) {
                return;
            }
            node = child;
            await toggleNode(node, node.lastElementChild, true);
        }
        const select = node.querySelector(":scope > .directory-picker-node-row .directory-picker-select");
        updateSelection(node.dataset.path, select);
        select?.scrollIntoView({ block: "nearest" });
    };

    const renderTree = async (path) => {
        tree.replaceChildren();
        const list = document.createElement("ul");
        list.setAttribute("role", "tree");
        list.append(nodeFor({ name: "Vault root", path: "" }));
        tree.append(list);
        updateSelection(path, null);
        await revealPath(path);
    };

    const close = () => {
        targetInput = null;
        if (typeof dialog.close === "function" && dialog.open) {
            dialog.close();
        } else {
            dialog.hidden = true;
        }
    };

    document.addEventListener("click", async (event) => {
        const opener = event.target.closest("[data-directory-picker-open]");
        if (!opener) {
            return;
        }
        event.preventDefault();
        targetInput = document.getElementById(opener.dataset.directoryPickerTarget);
        if (!targetInput) {
            return;
        }
        if (typeof dialog.showModal === "function") {
            dialog.showModal();
        } else {
            dialog.hidden = false;
        }
        await renderTree(targetInput.value);
    });

    dialog.querySelectorAll("[data-directory-picker-close]").forEach((button) => {
        button.addEventListener("click", close);
    });
    dialog.querySelector("[data-directory-picker-apply]")?.addEventListener("click", () => {
        if (!targetInput) {
            return;
        }
        targetInput.value = selectedPath;
        targetInput.dispatchEvent(new Event("input", { bubbles: true }));
        targetInput.dispatchEvent(new Event("change", { bubbles: true }));
        close();
    });
    dialog.addEventListener("click", (event) => {
        if (event.target === dialog) {
            close();
        }
    });
});
