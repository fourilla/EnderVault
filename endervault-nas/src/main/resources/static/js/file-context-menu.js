document.addEventListener("DOMContentLoaded", () => {
    const workspace = document.querySelector(".workspace");
    const bulkForm = document.getElementById("bulkActionForm");
    const uploadButton = document.getElementById("uploadButton");
    const createDirectoryButton = document.getElementById("createDirectoryButton");

    if (!workspace || !bulkForm) {
        return;
    }

    const {
        requestJson,
        requestJsonResolvingConflicts,
        showNotification,
        showToast,
        copyText,
        csrfPair,
        contextMenus
    } = window.EnderVault;

    const itemSelector = "[data-context-item='true']";
    const checkboxSelector = 'input[name="items"][form="bulkActionForm"]';
    const contextActions = [];
    const extensionActions = [];

    let activeMenu = null;
    let activeContext = null;
    let activeContextTarget = null;

    document.body.classList.add("context-menu-enhanced");

    const currentPath = () => bulkForm.querySelector('input[name="path"]')?.value || "";

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
        const body = await requestJsonResolvingConflicts("/files/rename", {
            method: "POST",
            body: formData
        });
        showNotification(body.notification);
        await refreshListing(body.redirectUrl || window.location.href);
    };

    const moveItemsToTrash = async (items) => {
        const body = await requestJson("/files/delete", {
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
        const body = await requestJson("/files/favorites/toggle", {
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

        const body = await requestJson("/files/transfer/buffer", {
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
        icon: "fas fa-file-zipper",
        visible: (context) => context.mode === "selection",
        run: (context) => downloadSelected(context.items)
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
        id: "new-directory",
        group: "background",
        label: "New directory",
        icon: "fas fa-folder-plus",
        visible: (context) => context.mode === "background" && Boolean(createDirectoryButton),
        run: () => createDirectoryButton.click()
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

    const actionLabel = (action, context) =>
        typeof action.label === "function" ? action.label(context) : action.label;

    const actionIcon = (action, context) =>
        typeof action.icon === "function" ? action.icon(context) : action.icon;

    const visibleActions = (context) => contextActions
            .filter((action) => !action.visible || action.visible(context));

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

        if (!workspace.contains(event.target)) {
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

    const closeMenu = () => {
        activeMenu?.remove();
        activeMenu = null;
        activeContext = null;
        activeContextTarget?.classList.remove("is-context-target");
        activeContextTarget = null;
        document.body.classList.remove("context-menu-open");
        contextMenus?.clear(closeMenu);
    };

    const createMenuButton = (action, context) => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "context-menu-item";
        button.dataset.contextAction = action.id;
        button.setAttribute("role", "menuitem");
        if (action.danger) {
            button.classList.add("danger");
        }

        const iconElement = document.createElement("i");
        iconElement.className = actionIcon(action, context);
        iconElement.setAttribute("aria-hidden", "true");
        const label = document.createElement("span");
        label.textContent = actionLabel(action, context);
        button.append(iconElement, label);
        button.addEventListener("click", async () => {
            closeMenu();
            try {
                await action.run(context);
            } catch (error) {
                showToast("error", error.message || "The action failed.");
            }
        });
        return button;
    };

    const renderMenu = (context, x, y) => {
        const actions = visibleActions(context);
        if (!actions.length) {
            return;
        }

        closeMenu();
        contextMenus?.open(closeMenu);
        const menu = document.createElement("div");
        menu.id = "fileContextMenu";
        menu.className = "context-menu";
        menu.setAttribute("role", "menu");
        menu.tabIndex = -1;

        let previousGroup = null;
        actions.forEach((action) => {
            if (previousGroup && previousGroup !== action.group) {
                const separator = document.createElement("div");
                separator.className = "context-menu-separator";
                separator.setAttribute("role", "separator");
                menu.append(separator);
            }
            previousGroup = action.group;
            menu.append(createMenuButton(action, context));
        });

        document.body.append(menu);
        const rect = menu.getBoundingClientRect();
        const left = Math.min(x, window.innerWidth - rect.width - 8);
        const top = Math.min(y, window.innerHeight - rect.height - 8);
        menu.style.left = `${Math.max(8, left)}px`;
        menu.style.top = `${Math.max(8, top)}px`;
        activeMenu = menu;
        activeContext = context;
        if (context.item?.element) {
            context.item.element.classList.add("is-context-target");
            activeContextTarget = context.item.element;
        }
        document.body.classList.add("context-menu-open");
    };

    document.addEventListener("contextmenu", (event) => {
        if (activeMenu?.contains(event.target)) {
            event.preventDefault();
            return;
        }
        const context = contextForEvent(event);
        if (!context) {
            closeMenu();
            return;
        }
        event.preventDefault();
        renderMenu(context, event.clientX, event.clientY);
    });

    document.addEventListener("click", (event) => {
        if (activeMenu && !activeMenu.contains(event.target)) {
            closeMenu();
        }
    });

    document.addEventListener("keydown", (event) => {
        if (!activeMenu) {
            return;
        }
        if (event.key === "Escape") {
            event.preventDefault();
            closeMenu();
            return;
        }
        if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault();
            const buttons = Array.from(activeMenu.querySelectorAll(".context-menu-item"));
            const currentIndex = buttons.indexOf(document.activeElement);
            const direction = event.key === "ArrowDown" ? 1 : -1;
            const nextIndex = currentIndex < 0
                    ? 0
                    : (currentIndex + direction + buttons.length) % buttons.length;
            buttons[nextIndex]?.focus();
            return;
        }
        if (event.key === "Enter" && document.activeElement?.matches(".context-menu-item")) {
            event.preventDefault();
            document.activeElement.click();
        }
    });

    window.addEventListener("resize", closeMenu);
    window.addEventListener("scroll", closeMenu, true);
    document.addEventListener("endervault:listing-refreshed", closeMenu);

    window.EnderVaultContextMenu = {
        registerAction,
        registerExtensionAction,
        extensionActions: () => [...extensionActions],
        close: closeMenu,
        activeContext: () => activeContext
    };
});
