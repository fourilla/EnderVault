(function () {
    const initialize = () => {
        const menus = window.EnderVaultContextMenus;
        if (!menus) {
            return;
        }

        const contextForEvent = (event) => {
            if (menus.pageScopeOwner()) return null;
            const context = menus.pageContextForEvent(event);
            return context && menus.globalActionsFor(context).length ? context : null;
        };

        menus.createActionMenu({
            menuId: "pageContextMenu",
            actions: [],
            contextForEvent,
            errorMessage: "Page action failed.",
            extraCloseEvents: ['endervault:spa-shell-ready', 'endervault:sticky-context-changed']
        });
    };
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initialize, { once: true });
    } else {
        initialize();
    }
})();
