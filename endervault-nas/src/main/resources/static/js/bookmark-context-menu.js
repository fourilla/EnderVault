document.addEventListener("DOMContentLoaded", () => {
    const workspace = document.querySelector(".workspace");
    const bulkForm = document.getElementById("bulkActionForm");

    if (!workspace || !bulkForm || !window.EnderVault) {
        return;
    }

    const {
        copyText,
        csrfPair,
        contextMenus,
        showToast
    } = window.EnderVault;

    const itemSelector = "[data-bookmark-context-item='true']";
    const checkboxSelector = 'input[name="bookmarkIds"][form="bulkActionForm"]';
    const actions = [];

    let activeMenu = null;
    let activeContext = null;
    let activeContextTarget = null;

    document.body.classList.add("context-menu-enhanced");

    const currentParentId = () => bulkForm.querySelector('input[name="parentId"]')?.value || "";
    const currentQuery = () => bulkForm.querySelector('input[name="q"]')?.value || "";

    const checkboxForItem = (item) => item?.querySelector(checkboxSelector) || null;

    const isItemSelected = (item) => Boolean(checkboxForItem(item)?.checked);

    const selectedCheckboxes = () => {
        if (window.EnderVaultFileSelection?.selectedItemCheckboxes) {
            return window.EnderVaultFileSelection.selectedItemCheckboxes()
                    .filter((checkbox) => checkbox.checked && checkbox.matches(checkboxSelector));
        }
        return Array.from(document.querySelectorAll(`${checkboxSelector}:checked`));
    };

    const itemFromCheckbox = (checkbox) => checkbox.closest(itemSelector);

    const metadataFor = (item) => ({
        element: item,
        id: item.dataset.bookmarkId || "",
        title: item.dataset.bookmarkTitle || "",
        type: item.dataset.bookmarkType || "",
        url: item.dataset.bookmarkUrl || "",
        external: item.dataset.bookmarkExternal === "true",
        favorite: item.dataset.bookmarkFavorite === "true",
        metadataEnabled: item.dataset.bookmarkMetadataEnabled === "true",
        openUrl: item.dataset.bookmarkOpenUrl || "",
        detailUrl: item.dataset.bookmarkDetailUrl || "",
        metadataUrl: item.dataset.bookmarkMetadataUrl || "/files/bookmarks/metadata",
        deleteUrl: item.dataset.bookmarkDeleteUrl || "/files/bookmarks/delete",
        get directory() {
            return this.type === "directory";
        },
        get link() {
            return this.type === "link";
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

    const appendHidden = (form, name, value) => {
        const input = document.createElement("input");
        input.type = "hidden";
        input.name = name;
        input.value = value == null ? "" : value;
        form.append(input);
    };

    const submitPost = (action, fields = {}) => {
        const form = document.createElement("form");
        form.method = "post";
        form.action = action;
        const csrf = csrfPair(bulkForm);
        if (csrf) {
            appendHidden(form, csrf.name, csrf.value);
        }
        Object.entries(fields).forEach(([name, value]) => appendHidden(form, name, value));
        document.body.append(form);
        form.submit();
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

    const openToolMenu = (name) => {
        const summary = document.querySelector(`[data-bookmark-tool="${name}"]`);
        summary?.click();
    };

    const deleteSingle = (item) => {
        if (!window.confirm(`Delete "${item.title}"?`)) {
            return;
        }
        submitPost(item.deleteUrl, {
            id: item.id,
            parentId: currentParentId(),
            q: currentQuery()
        });
    };

    const deleteSelected = (items) => {
        if (!window.confirm(`Delete ${items.length} selected bookmark items?`)) {
            return;
        }
        bulkForm.action = bulkForm.dataset.bookmarkDeleteSelectedUrl || "/files/bookmarks/delete-selected";
        bulkForm.method = "post";
        bulkForm.submit();
    };

    const refreshMetadata = (item) => submitPost(item.metadataUrl, {
        id: item.id,
        parentId: currentParentId(),
        q: currentQuery()
    });

    const copyBookmarkUrl = async (item) => {
        const copied = await copyText(item.url);
        showToast(copied ? "success" : "warning", copied ? "Bookmark URL copied." : "Clipboard is not available.");
    };

    const toggleFavorite = async (item) => {
        const body = await window.EnderVaultFavorites?.toggleBookmark(item.id);
        item.favorite = Boolean(body?.active);
        item.element.dataset.bookmarkFavorite = String(item.favorite);
    };

    const registerAction = (action) => {
        actions.push(action);
        return action;
    };

    registerAction({
        id: "open",
        group: "primary",
        label: (context) => context.item.directory ? "Open directory" : "Open in new tab",
        icon: (context) => context.item.directory ? "fas fa-folder-open" : "fas fa-arrow-up-right-from-square",
        visible: (context) => context.mode === "single",
        run: (context) => navigateTo(context.item.openUrl, context.item.link)
    });

    registerAction({
        id: "details",
        group: "primary",
        label: "Details",
        icon: "fas fa-circle-info",
        visible: (context) => context.mode === "single",
        run: (context) => navigateTo(context.item.detailUrl)
    });

    registerAction({
        id: "copy-url",
        group: "organize",
        label: "Copy URL",
        icon: "fas fa-copy",
        visible: (context) => context.mode === "single" && context.item.link && Boolean(context.item.url),
        run: (context) => copyBookmarkUrl(context.item)
    });

    registerAction({
        id: "toggle-favorite",
        group: "organize",
        label: (context) => context.item.favorite ? "Remove from favorites" : "Add to favorites",
        icon: "fas fa-star",
        visible: (context) => context.mode === "single",
        run: (context) => toggleFavorite(context.item)
    });

    registerAction({
        id: "refresh-metadata",
        group: "organize",
        label: "Refresh metadata",
        icon: "fas fa-wand-magic-sparkles",
        visible: (context) => context.mode === "single"
                && context.item.link
                && context.item.external
                && context.item.metadataEnabled,
        run: (context) => refreshMetadata(context.item)
    });

    registerAction({
        id: "delete",
        group: "danger",
        label: "Delete",
        icon: "fas fa-trash-can",
        danger: true,
        visible: (context) => context.mode === "single",
        run: (context) => deleteSingle(context.item)
    });

    registerAction({
        id: "delete-selected",
        group: "danger",
        label: (context) => `Delete ${context.items.length} selected`,
        icon: "fas fa-trash-can",
        danger: true,
        visible: (context) => context.mode === "selection",
        run: (context) => deleteSelected(context.items)
    });

    registerAction({
        id: "create-link",
        group: "background",
        label: "Create link",
        icon: "fas fa-link",
        visible: (context) => context.mode === "background",
        run: () => openToolMenu("create-link")
    });

    registerAction({
        id: "create-directory",
        group: "background",
        label: "Create directory",
        icon: "fas fa-folder-plus",
        visible: (context) => context.mode === "background",
        run: () => openToolMenu("create-directory")
    });

    registerAction({
        id: "bulk-add",
        group: "background",
        label: "Bulk add links",
        icon: "fas fa-list-ul",
        visible: (context) => context.mode === "background",
        run: () => openToolMenu("bulk-add")
    });

    const actionLabel = (action, context) =>
        typeof action.label === "function" ? action.label(context) : action.label;

    const actionIcon = (action, context) =>
        typeof action.icon === "function" ? action.icon(context) : action.icon;

    const visibleActions = (context) => actions
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

        if (!workspace.contains(event.target) || isNativeContextTarget(event.target)) {
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
                showToast("error", error.message || "Bookmark action failed.");
            }
        });
        return button;
    };

    const renderMenu = (context, x, y) => {
        const visible = visibleActions(context);
        if (!visible.length) {
            return;
        }

        closeMenu();
        contextMenus?.open(closeMenu);
        const menu = document.createElement("div");
        menu.id = "bookmarkContextMenu";
        menu.className = "context-menu";
        menu.setAttribute("role", "menu");
        menu.tabIndex = -1;

        let previousGroup = null;
        visible.forEach((action) => {
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
});
