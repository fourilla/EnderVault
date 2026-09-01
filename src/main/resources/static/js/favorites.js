document.addEventListener("DOMContentLoaded", () => {
    const {
        requestJson,
        showNotification,
        showToast,
        csrfPair,
        contextMenus
    } = window.EnderVault;

    let activeSidebarMenu = null;
    let activeSidebarFavorite = null;

    const dispatchFavoritesChanged = (detail) => {
        document.dispatchEvent(new CustomEvent("endervault:favorites-changed", { detail }));
    };

    const sidebarFavoriteLinks = () =>
        Array.from(document.querySelectorAll("[data-sidebar-favorites-list] [data-favorite-sidebar-path]"));

    const sidebarSibling = (link, direction) => {
        const links = sidebarFavoriteLinks();
        const index = links.indexOf(link);
        if (index < 0) {
            return null;
        }
        return direction === "up" ? links[index - 1] : links[index + 1];
    };

    const formDataWithCsrf = () => {
        const formData = new FormData();
        const csrf = csrfPair();
        if (csrf) {
            formData.append(csrf.name, csrf.value);
        }
        return formData;
    };

    const remove = async (path) => {
        const formData = formDataWithCsrf();
        formData.append("path", path);
        const body = await requestJson("/api/v1/favorites/remove", {
            method: "POST",
            body: formData
        });
        showNotification(body.notification);
        dispatchFavoritesChanged({ action: "remove", path, active: false });
        return body;
    };

    const move = async (path, direction) => {
        const formData = formDataWithCsrf();
        formData.append("path", path);
        formData.append("direction", direction);
        const body = await requestJson("/api/v1/favorites/move", {
            method: "POST",
            body: formData
        });
        showNotification(body.notification);
        dispatchFavoritesChanged({ action: "move", path, direction });
        return body;
    };

    function closeSidebarFavoriteMenu() {
        activeSidebarMenu?.remove();
        activeSidebarMenu = null;
        activeSidebarFavorite?.classList.remove("is-context-target");
        activeSidebarFavorite = null;
        contextMenus?.clear(closeSidebarFavoriteMenu);
    }

    const positionSidebarMenu = (menu, x, y) => {
        menu.style.left = "0";
        menu.style.top = "0";
        document.body.append(menu);

        const margin = 8;
        const rect = menu.getBoundingClientRect();
        const left = Math.min(x, window.innerWidth - rect.width - margin);
        const top = Math.min(y, window.innerHeight - rect.height - margin);
        menu.style.left = `${Math.max(margin, left)}px`;
        menu.style.top = `${Math.max(margin, top)}px`;
    };

    const runSidebarMenuAction = async (action) => {
        try {
            await action();
        } catch (error) {
            showToast("error", error.message || "Favorite action failed.");
        } finally {
            closeSidebarFavoriteMenu();
        }
    };

    const sidebarMenuButton = ({ icon, label, danger = false, action }) => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = `context-menu-item${danger ? " danger" : ""}`;
        button.setAttribute("role", "menuitem");
        button.innerHTML = `
            <i class="${icon}" aria-hidden="true"></i>
            <span></span>
        `;
        button.querySelector("span").textContent = label;
        button.addEventListener("click", () => runSidebarMenuAction(action));
        return button;
    };

    const sidebarMenuSeparator = () => {
        const separator = document.createElement("div");
        separator.className = "context-menu-separator";
        return separator;
    };

    const moveSidebarFavorite = async (link, direction) => {
        const sibling = sidebarSibling(link, direction);
        if (!sibling) {
            return;
        }

        await move(link.dataset.favoriteSidebarPath || "", direction);
    };

    const removeSidebarFavoriteViaMenu = async (link) => {
        const path = link.dataset.favoriteSidebarPath || "";
        await remove(path);
    };

    const showSidebarFavoriteMenu = (event, link) => {
        closeSidebarFavoriteMenu();
        contextMenus?.open(closeSidebarFavoriteMenu);

        const menu = document.createElement("div");
        menu.className = "context-menu";
        menu.setAttribute("role", "menu");

        if (link.dataset.favoriteBookmarkLink === "true") {
            menu.append(sidebarMenuButton({
                icon: "fas fa-arrow-up-right-from-square",
                label: "Open in new tab",
                action: () => {
                    window.open(link.dataset.favoriteDirectOpenUrl || link.href, "_blank", "noopener,noreferrer");
                }
            }));
            menu.append(sidebarMenuButton({
                icon: "fas fa-circle-info",
                label: "Details",
                action: () => {
                    const url = link.dataset.favoriteDetailUrl || link.href;
                    window.EnderVault?.navigate?.(url) || window.location.assign(url);
                }
            }));
        } else {
            menu.append(sidebarMenuButton({
                icon: "fas fa-arrow-up-right-from-square",
                label: "Open",
                action: () => {
                    if (link.target === "_blank") {
                        window.open(link.href, "_blank", "noopener,noreferrer");
                        return;
                    }
                    window.EnderVault?.navigate?.(link.href) || window.location.assign(link.href);
                }
            }));
        }

        if (sidebarSibling(link, "up") || sidebarSibling(link, "down")) {
            menu.append(sidebarMenuSeparator());
        }

        if (sidebarSibling(link, "up")) {
            menu.append(sidebarMenuButton({
                icon: "fas fa-arrow-up",
                label: "Move up",
                action: () => moveSidebarFavorite(link, "up")
            }));
        }

        if (sidebarSibling(link, "down")) {
            menu.append(sidebarMenuButton({
                icon: "fas fa-arrow-down",
                label: "Move down",
                action: () => moveSidebarFavorite(link, "down")
            }));
        }

        menu.append(sidebarMenuSeparator());
        menu.append(sidebarMenuButton({
            icon: "fas fa-star-half-stroke",
            label: "Remove from favorites",
            danger: true,
            action: () => removeSidebarFavoriteViaMenu(link)
        }));

        const globalActions = window.EnderVaultContextMenus?.globalActionsFor({
            mode: "sidebar-favorite",
            item: { element: link },
            event
        }) || [];
        if (globalActions.length) {
            menu.append(sidebarMenuSeparator());
            globalActions.forEach((action) => menu.append(sidebarMenuButton({
                icon: action.icon,
                label: action.label,
                danger: action.danger,
                action: action.run
            })));
        }

        activeSidebarMenu = menu;
        activeSidebarFavorite = link;
        activeSidebarFavorite.classList.add("is-context-target");
        positionSidebarMenu(menu, event.clientX, event.clientY);
    };

    const bindSidebarFavoriteContextMenu = () => {
        document.addEventListener("contextmenu", (event) => {
            if (activeSidebarMenu?.contains(event.target)) {
                event.preventDefault();
                return;
            }

            const link = event.target.closest("[data-favorite-sidebar-path]");
            if (!link) {
                closeSidebarFavoriteMenu();
                return;
            }

            event.preventDefault();
            showSidebarFavoriteMenu(event, link);
        });

        document.addEventListener("click", (event) => {
            if (!activeSidebarMenu || activeSidebarMenu.contains(event.target)) {
                return;
            }
            closeSidebarFavoriteMenu();
        });

        document.addEventListener("keydown", (event) => {
            if (event.key === "Escape") {
                closeSidebarFavoriteMenu();
            }
        });

        window.addEventListener("resize", closeSidebarFavoriteMenu);
        document.addEventListener("scroll", closeSidebarFavoriteMenu, true);
    };

    bindSidebarFavoriteContextMenu();
});
