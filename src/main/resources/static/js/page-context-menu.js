(function () {
    const nativeContextSelector = [
        "a",
        "button",
        "input",
        "textarea",
        "select",
        "label",
        "summary",
        "iframe",
        "object",
        "embed",
        "img",
        "video",
        "audio",
        "canvas",
        "[contenteditable='true']",
        ".app-topbar",
        ".sidebar",
        ".context-menu",
        ".sticky-note-layer",
        "dialog"
    ].join(", ");

    const hasTextSelection = () => {
        const selection = window.getSelection?.();
        return Boolean(selection && !selection.isCollapsed && selection.toString().trim());
    };

    document.addEventListener("DOMContentLoaded", () => {
        const appMain = document.querySelector(".app-main");
        const menus = window.EnderVaultContextMenus;
        if (!appMain || !menus) {
            return;
        }

        const contextForEvent = (event) => {
            if (menus.pageScopeOwner() || !appMain.contains(event.target)) {
                return null;
            }
            if (event.target.closest(nativeContextSelector) || hasTextSelection()) {
                return null;
            }

            const context = {
                mode: "page-background",
                item: null,
                items: [],
                event
            };
            return menus.globalActionsFor(context).length ? context : null;
        };

        menus.createActionMenu({
            menuId: "pageContextMenu",
            actions: [],
            contextForEvent,
            errorMessage: "Page action failed."
        });
    });
})();
