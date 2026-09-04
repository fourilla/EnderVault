(function () {
    const globalActions = [];
    let pageScope = null;

    const actionValue = (action, context, property) =>
        typeof action[property] === "function" ? action[property](context) : action[property];

    const visibleActions = (actions, context) =>
        actions.filter((action) => !action.visible || action.visible(context));

    const registerGlobalAction = (action) => {
        if (!action?.id || typeof action.run !== "function") {
            return;
        }
        const existingIndex = globalActions.findIndex((candidate) => candidate.id === action.id);
        if (existingIndex >= 0) {
            globalActions.splice(existingIndex, 1, action);
            return;
        }
        globalActions.push(action);
    };

    const globalActionsFor = (context) => visibleActions(globalActions, context).map((action) => ({
        id: action.id,
        group: action.group,
        label: actionValue(action, context, "label"),
        icon: actionValue(action, context, "icon"),
        danger: Boolean(action.danger),
        run: () => action.run(context)
    }));

    const createActionMenu = ({
        menuId,
        actions,
        contextForEvent,
        pageScope: owner = "",
        errorMessage = "The action failed.",
        extraCloseEvents = []
    }) => {
        if (!window.EnderVault || (!Array.isArray(actions) && typeof actions !== "function")
                || typeof contextForEvent !== "function" || (owner && pageScope)) {
            return null;
        }

        const scope = owner ? { owner } : null;
        if (scope) pageScope = scope;
        const currentActions = () => typeof actions === "function" ? actions() : actions;
        const listeners = [];
        const listen = (target, type, listener, options) => {
            target.addEventListener(type, listener, options);
            listeners.push(() => target.removeEventListener(type, listener, options));
        };
        const { contextMenus, showToast } = window.EnderVault;
        let disposed = false;
        let activeMenu = null;
        let activeContext = null;
        let activeContextTarget = null;

        document.body.classList.add("context-menu-enhanced");

        const closeMenu = () => {
            if (!activeMenu) return;
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
            iconElement.className = actionValue(action, context, "icon");
            iconElement.setAttribute("aria-hidden", "true");
            const label = document.createElement("span");
            label.textContent = actionValue(action, context, "label");
            button.append(iconElement, label);
            button.addEventListener("click", async () => {
                if (disposed) return;
                closeMenu();
                try {
                    await action.run(context);
                } catch (error) {
                    showToast("error", error.message || errorMessage);
                }
            });
            return button;
        };

        const renderMenu = (context, x, y) => {
            const visible = visibleActions([...currentActions(), ...globalActions], context);
            closeMenu();
            if (!visible.length) {
                return;
            }

            contextMenus?.open(closeMenu);
            const menu = document.createElement("div");
            menu.id = menuId;
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

        listen(document, "contextmenu", (event) => {
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

        listen(document, "click", (event) => {
            if (activeMenu && !activeMenu.contains(event.target)) {
                closeMenu();
            }
        });

        listen(document, "keydown", (event) => {
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

        listen(window, "resize", closeMenu);
        listen(window, "scroll", closeMenu, true);
        extraCloseEvents.forEach((eventName) => listen(document, eventName, closeMenu));

        return {
            close: closeMenu,
            dispose: () => {
                if (disposed) return;
                disposed = true;
                closeMenu();
                listeners.forEach((remove) => remove());
                if (scope && pageScope === scope) pageScope = null;
            },
            activeContext: () => activeContext,
            visibleActions: (context) => visibleActions([...currentActions(), ...globalActions], context)
        };
    };

    window.EnderVaultContextMenus = {
        createActionMenu,
        registerGlobalAction,
        globalActionsFor,
        pageScopeOwner: () => pageScope?.owner || ""
    };
})();
