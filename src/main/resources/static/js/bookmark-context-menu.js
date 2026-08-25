document.addEventListener("DOMContentLoaded", () => {
    const workspace = document.querySelector(".workspace");
    const appMain = document.querySelector(".app-main");
    const bulkForm = document.getElementById("bulkActionForm");
    const createDirectoryButton = document.getElementById("createBookmarkDirectoryButton");
    const createLinkButton = document.getElementById("createBookmarkLinkButton");
    const bulkAddButton = document.getElementById("bulkAddBookmarksButton");

    if (!workspace || !bulkForm || !window.EnderVault) {
        return;
    }

    if (!window.EnderVaultContextMenus?.claimPageScope("bookmarks")) {
        return;
    }

    const {
        copyText,
        showToast
    } = window.EnderVault;

    const itemSelector = "[data-bookmark-context-item='true']";
    const checkboxSelector = 'input[name="bookmarkIds"][form="bulkActionForm"]';
    const actions = [];

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
        metadataUrl: item.dataset.bookmarkMetadataUrl || "/api/v1/bookmarks/metadata",
        deleteUrl: item.dataset.bookmarkDeleteUrl || "/api/v1/bookmarks/delete",
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

    const deleteSingle = (item) => window.EnderVaultBookmarks?.deleteItem(item);

    const deleteSelected = (items) => window.EnderVaultBookmarks?.deleteSelected(items.length);

    const refreshMetadata = (item) => window.EnderVaultBookmarks?.refreshMetadata(item);

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
        label: "Add link",
        icon: "fas fa-link",
        visible: (context) => context.mode === "background" && Boolean(createLinkButton),
        run: () => createLinkButton.click()
    });

    registerAction({
        id: "create-directory",
        group: "background",
        label: "New directory",
        icon: "fas fa-folder-plus",
        visible: (context) => context.mode === "background" && Boolean(createDirectoryButton),
        run: () => createDirectoryButton.click()
    });

    registerAction({
        id: "bulk-add",
        group: "background",
        label: "Bulk add links",
        icon: "fas fa-list-ul",
        visible: (context) => context.mode === "background" && Boolean(bulkAddButton),
        run: () => bulkAddButton.click()
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
        if ((!inWorkspace && !inMainBackground) || isNativeContextTarget(event.target)) {
            return null;
        }

        return {
            mode: "background",
            item: null,
            items: [],
            event
        };
    };

    window.EnderVaultContextMenus?.createActionMenu({
        menuId: "bookmarkContextMenu",
        actions,
        contextForEvent,
        errorMessage: "Bookmark action failed."
    });
});
