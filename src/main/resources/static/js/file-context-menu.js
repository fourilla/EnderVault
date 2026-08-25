document.addEventListener("DOMContentLoaded", () => {
    const workspace = document.querySelector(".workspace");
    const appMain = document.querySelector(".app-main");
    const bulkForm = document.getElementById("bulkActionForm");
    const uploadButton = document.getElementById("uploadButton");
    const createFileButton = document.getElementById("createFileButton");
    const createDirectoryButton = document.getElementById("createDirectoryButton");

    if (!workspace || !bulkForm) {
        return;
    }

    if (!window.EnderVaultContextMenus?.claimPageScope("files")) {
        return;
    }

    const {
        requestJson,
        requestJsonResolvingConflicts,
        showNotification,
        showToast,
        copyText,
        csrfPair
    } = window.EnderVault;

    const itemSelector = "[data-context-item='true']";
    const checkboxSelector = 'input[name="items"][form="bulkActionForm"]';
    const contextActions = [];
    const extensionActions = [];

    const currentPath = () => bulkForm.querySelector('input[name="path"]')?.value || "";

    const fileRequestsEnabled = bulkForm.dataset.fileRequestsEnabled === "true";

    const itemNameFromCheckbox = (checkbox) => checkbox?.value || "";

    const checkboxForItem = (item) => item?.querySelector(checkboxSelector) || null;

    const isItemSelected = (item) => Boolean(checkboxForItem(item)?.checked);

    const selectedCheckboxes = () => {
        if (window.EnderVaultFileSelection?.selectedItemCheckboxes) {
            return window.EnderVaultFileSelection.selectedItemCheckboxes()
                    .filter((checkbox) => checkbox.checked);
        }
        return Array.from(document.querySelectorAll(`${checkboxSelector}:checked`));
    };

    const itemFromCheckbox = (checkbox) => checkbox.closest(itemSelector);

    const extensionOf = (name) => {
        const index = name.lastIndexOf(".");
        if (index <= 0 || index === name.length - 1) {
            return "";
        }
        return name.slice(index + 1).toLowerCase();
    };

    const metadataFor = (item) => ({
        element: item,
        name: item.dataset.itemName || itemNameFromCheckbox(checkboxForItem(item)),
        path: item.dataset.itemPath || "",
        kind: item.dataset.itemKind || "file",
        openUrl: item.dataset.itemOpenUrl || "",
        detailUrl: item.dataset.itemDetailUrl || "",
        downloadUrl: item.dataset.itemDownloadUrl || "",
        previewUrl: item.dataset.itemPreviewUrl || "",
        favorite: item.dataset.itemFavorite === "true",
        get directory() {
            return this.kind === "directory";
        },
        get file() {
            return this.kind === "file";
        },
        get extension() {
            return extensionOf(this.name);
        }
    });

    const selectedItems = () => selectedCheckboxes()
            .map(itemFromCheckbox)
            .filter(Boolean)
            .map(metadataFor);

    const clearSelection = () => {
        if (window.EnderVaultFileSelection?.clear) {
            window.EnderVaultFileSelection.clear();
            return;
        }
        selectedCheckboxes().forEach((checkbox) => {
            checkbox.checked = false;
        });
    };

    const isNativeContextTarget = (target) =>
        Boolean(target.closest("input, textarea, select, button, label, summary, [contenteditable='true']"));

    const appendHidden = (formData, name, value) => {
        formData.append(name, value == null ? "" : value);
    };

    const formDataForItems = (names) => {
        const formData = new FormData();
        const csrf = csrfPair();
        if (csrf) {
            appendHidden(formData, csrf.name, csrf.value);
        }
        appendHidden(formData, "path", currentPath());
        names.forEach((name) => appendHidden(formData, "items", name));
        return formData;
    };

    const formDataForSingle = (item) => {
        const formData = new FormData();
        const csrf = csrfPair();
        if (csrf) {
            appendHidden(formData, csrf.name, csrf.value);
        }
        appendHidden(formData, "path", currentPath());
        appendHidden(formData, "item", item.name);
        return formData;
    };

    const refreshListing = async (url = window.location.href) => {
        if (window.EnderVaultFileBrowser?.refreshListing) {
            await window.EnderVaultFileBrowser.refreshListing(url);
            return;
        }
        window.location.href = url;
    };

    const navigateTo = (url, newTab = false) => {
        if (!url) {
            return;
        }
        if (newTab) {
            window.open(url, "_blank", "noopener,noreferrer");
            return;
        }
        window.location.href = url;
    };

    const createFileRequestFor = (destinationPath) => {
        const query = new URLSearchParams();
        if (destinationPath) {
            query.set("destinationPath", destinationPath);
        }
        const suffix = query.toString();
        navigateTo(`/admin/file-requests${suffix ? `?${suffix}` : ""}`);
    };

    const downloadSelected = (items) => {
        if (items.length === 1 && items[0].downloadUrl) {
            navigateTo(items[0].downloadUrl);
            return;
        }

        const query = new URLSearchParams();
        if (currentPath()) {
            query.set("path", currentPath());
        }
        items.forEach((item) => query.append("items", item.name));
        navigateTo(`/files/download.zip?${query.toString()}`);
    };

    const renameItem = async (item) => {
        const newName = window.prompt("Rename", item.name);
        if (newName == null) {
            return;
        }

        const trimmedName = newName.trim();
        if (!trimmedName || trimmedName === item.name) {
            return;
        }

        const formData = formDataForSingle(item);
        formData.set("newName", trimmedName);
        formData.set("conflictPolicy", "ask");
        const body = await requestJsonResolvingConflicts("/api/v1/files/rename", {
            method: "POST",
            body: formData
        });
        showNotification(body.notification);
        await refreshListing(body.redirectUrl || window.location.href);
    };

    const moveItemsToTrash = async (items) => {
        const body = await requestJson("/api/v1/files/trash", {
            method: "POST",
            body: formDataForItems(items.map((item) => item.name))
        });
        showNotification(body.notification);
        if (body.task) {
            window.EnderVaultServerTasks?.track(body.task, {
                refreshUrl: body.redirectUrl || window.location.href
            });
            return;
        }
        await refreshListing(body.redirectUrl || window.location.href);
    };

    const shareAndCopy = async (item) => {
        const formData = formDataForSingle(item);
        const body = await requestJson("/files/share", {
            method: "POST",
            body: formData
        });

        const url = body.shareLink?.url || body.notification?.actionValue || "";
        if (url) {
            const copied = await copyText(url);
            if (copied) {
                showToast("success", "Share link created and copied.");
                return;
            }
        }
        showNotification(body.notification);
    };

    const favoriteLabel = (item) => item.favorite ? "Remove from favorites" : "Add to favorites";

    const toggleFavorite = async (item) => {
        if (window.EnderVaultFavorites?.togglePath) {
            await window.EnderVaultFavorites.togglePath(item.path);
            item.element.dataset.itemFavorite = String(!item.favorite);
            return;
        }

        const formData = new FormData();
        const csrf = csrfPair();
        if (csrf) {
            appendHidden(formData, csrf.name, csrf.value);
        }
        appendHidden(formData, "path", item.path);
        const body = await requestJson("/api/v1/favorites/toggle", {
            method: "POST",
            body: formData
        });
        showNotification(body.notification);
        item.element.dataset.itemFavorite = String(body.active);
    };

    const addToTransferBuffer = async (items) => {
        if (window.EnderVaultTransferBuffer?.addItems) {
            await window.EnderVaultTransferBuffer.addItems(items.map((item) => item.name), {
                clearSelection: items.length > 1
            });
            return;
        }

        const body = await requestJson("/api/v1/files/transfer-buffer", {
            method: "POST",
            body: formDataForItems(items.map((item) => item.name))
        });
        showNotification(body.notification);
    };

    const pasteTransferBuffer = async (operation) => {
        if (window.EnderVaultTransferBuffer?.paste) {
            await window.EnderVaultTransferBuffer.paste(operation);
            return;
        }
        showToast("warning", "Transfer buffer is not available.");
    };

    const transferBufferReady = () =>
        document.querySelector("[data-transfer-buffer-region] .transfer-buffer-panel")
        && document.querySelector("[data-transfer-buffer-region]")?.dataset.transferPasteEnabled === "true";

    const registerAction = (action) => {
        contextActions.push(action);
        return action;
    };

    const registerExtensionAction = (action) => {
        extensionActions.push(action);
        registerAction({
            ...action,
            visible: (context) => context.mode === "single"
                    && context.item?.file
                    && (action.extensions || []).includes(context.item.extension)
                    && (action.visible ? action.visible(context) : true)
        });
    };

    registerAction({
        id: "open",
        group: "primary",
        label: (context) => context.item.directory ? "Open" : "Details",
        icon: (context) => context.item.directory ? "fas fa-folder-open" : "fas fa-circle-info",
        visible: (context) => context.mode === "single",
        run: (context) => navigateTo(context.item.openUrl || context.item.detailUrl)
    });

    registerAction({
        id: "preview",
        group: "primary",
        label: "Preview",
        icon: "fas fa-eye",
        visible: (context) => context.mode === "single" && Boolean(context.item.previewUrl),
        run: (context) => navigateTo(context.item.previewUrl, true)
    });

    registerAction({
        id: "download",
        group: "transfer",
        label: "Download",
        icon: "fas fa-download",
        visible: (context) => context.mode === "single" && Boolean(context.item.downloadUrl),
        run: (context) => navigateTo(context.item.downloadUrl)
    });

    registerAction({
        id: "download-selected",
        group: "transfer",
        label: (context) => `Download ${context.items.length} selected`,
        icon: "fas fa-download",
        visible: (context) => context.mode === "selection",
        run: (context) => downloadSelected(context.items)
    });

    registerAction({
        id: "compress-to-zip",
        group: "transfer",
        label: (context) => context.mode === "selection"
                ? `Compress ${context.items.length} selected to ZIP`
                : "Compress to ZIP",
        icon: "fas fa-file-zipper",
        visible: (context) => context.mode === "single" || context.mode === "selection",
        run: (context) => window.EnderVaultArchiveCreation?.open(context.items)
    });

    registerAction({
        id: "favorite",
        group: "organize",
        label: (context) => favoriteLabel(context.item),
        icon: "fas fa-star",
        visible: (context) => context.mode === "single",
        run: (context) => toggleFavorite(context.item)
    });

    registerAction({
        id: "add-to-buffer",
        group: "organize",
        label: (context) => context.mode === "selection"
                ? `Add ${context.items.length} selected to transfer buffer`
                : "Add to transfer buffer",
        icon: "fas fa-layer-group",
        visible: (context) => context.mode === "single" || context.mode === "selection",
        run: (context) => addToTransferBuffer(context.items)
    });

    registerAction({
        id: "share-copy",
        group: "organize",
        label: "Create share link and copy",
        icon: "fas fa-link",
        visible: (context) => context.mode === "single",
        run: (context) => shareAndCopy(context.item)
    });

    registerAction({
        id: "create-file-request-for-directory",
        group: "organize",
        label: "Create file request here",
        icon: "fas fa-inbox",
        visible: (context) => fileRequestsEnabled
                && context.mode === "single"
                && context.item?.directory,
        run: (context) => createFileRequestFor(context.item.path)
    });

    registerAction({
        id: "rename",
        group: "mutate",
        label: "Rename",
        icon: "fas fa-pen-to-square",
        visible: (context) => context.mode === "single",
        run: (context) => renameItem(context.item)
    });

    registerAction({
        id: "move-to-trash",
        group: "danger",
        label: (context) => context.mode === "selection"
                ? `Move ${context.items.length} selected to trash`
                : "Move to trash",
        icon: "fas fa-trash-can",
        danger: true,
        visible: (context) => context.mode === "single" || context.mode === "selection",
        run: (context) => moveItemsToTrash(context.items)
    });

    registerAction({
        id: "upload",
        group: "background",
        label: "Upload files",
        icon: "fas fa-upload",
        visible: (context) => context.mode === "background" && Boolean(uploadButton),
        run: () => uploadButton.click()
    });

    registerAction({
        id: "new-file",
        group: "background",
        label: "New file",
        icon: "fas fa-file-circle-plus",
        visible: (context) => context.mode === "background" && Boolean(createFileButton),
        run: () => createFileButton.click()
    });

    registerAction({
        id: "new-directory",
        group: "background",
        label: "New directory",
        icon: "fas fa-folder-plus",
        visible: (context) => context.mode === "background" && Boolean(createDirectoryButton),
        run: () => createDirectoryButton.click()
    });

    registerAction({
        id: "create-file-request-here",
        group: "background",
        label: "Create file request here",
        icon: "fas fa-inbox",
        visible: (context) => fileRequestsEnabled && context.mode === "background",
        run: () => createFileRequestFor(currentPath())
    });

    registerAction({
        id: "move-here",
        group: "background-transfer",
        label: "Move here",
        icon: "fas fa-file-import",
        visible: (context) => context.mode === "background" && transferBufferReady(),
        run: () => pasteTransferBuffer("move")
    });

    registerAction({
        id: "copy-here",
        group: "background-transfer",
        label: "Copy here",
        icon: "fas fa-copy",
        visible: (context) => context.mode === "background" && transferBufferReady(),
        run: () => pasteTransferBuffer("copy")
    });

    const contextForEvent = (event) => {
        const targetItem = event.target.closest(itemSelector);
        if (targetItem) {
            const selected = selectedItems();
            const targetSelected = isItemSelected(targetItem);
            const useSelection = selected.length > 1 && targetSelected;
            if (selected.length > 0 && !targetSelected) {
                clearSelection();
            }
            const item = metadataFor(targetItem);
            return {
                mode: useSelection ? "selection" : "single",
                item,
                items: useSelection ? selected : [item],
                event
            };
        }

        const inWorkspace = workspace.contains(event.target);
        const inMainBackground = appMain?.contains(event.target)
                && !event.target.closest(".topbar, header, aside, dialog, .context-menu");
        if (!inWorkspace && !inMainBackground) {
            return null;
        }

        if (isNativeContextTarget(event.target)) {
            return null;
        }

        return {
            mode: "background",
            item: null,
            items: [],
            event
        };
    };

    const menu = window.EnderVaultContextMenus?.createActionMenu({
        menuId: "fileContextMenu",
        actions: contextActions,
        contextForEvent,
        errorMessage: "The action failed.",
        extraCloseEvents: ["endervault:listing-refreshed"]
    });

    window.EnderVaultContextMenu = {
        registerAction,
        registerExtensionAction,
        extensionActions: () => [...extensionActions],
        close: () => menu?.close(),
        activeContext: () => menu?.activeContext()
    };
});
