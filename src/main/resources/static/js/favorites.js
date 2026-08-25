document.addEventListener("DOMContentLoaded", () => {
    const {
        requestJson,
        submitJsonForm,
        showNotification,
        showToast,
        csrfPair,
        contextMenus
    } = window.EnderVault;

    let activeSidebarMenu = null;
    let activeSidebarFavorite = null;

    const favoriteUrl = (favorite) => {
        if (favorite.openUrl) {
            return favorite.openUrl;
        }
        const query = new URLSearchParams({ path: favorite.path }).toString();
        return favorite.directory ? `/files?${query}` : `/files/detail?${query}`;
    };

    const setButtonState = (button, active) => {
        if (!button) {
            return;
        }

        const label = active ? "Remove from favorites" : "Add to favorites";
        button.classList.toggle("is-favorite", active);
        button.title = label;
        button.setAttribute("aria-label", label);
    };

    const updateMatchingButtons = (path, active) => {
        document.querySelectorAll("form[data-favorite-path]").forEach((form) => {
            if (form.dataset.favoritePath !== path) {
                return;
            }
            setButtonState(form.querySelector(".favorite-toggle"), active);
        });
        document.querySelectorAll("[data-context-item]").forEach((item) => {
            if (item.dataset.itemPath === path) {
                item.dataset.itemFavorite = String(active);
            }
        });
        document.querySelectorAll("[data-bookmark-context-item]").forEach((item) => {
            if (`bookmark:${item.dataset.bookmarkId}` === path) {
                item.dataset.bookmarkFavorite = String(active);
            }
        });
    };

    const favoriteElement = (root, attribute, path) =>
        Array.from(root.querySelectorAll(`[${attribute}]`))
                .find((element) => element.getAttribute(attribute) === path);

    const sidebarList = () => document.querySelector("[data-sidebar-favorites-list]");

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

    const addSidebarFavorite = (favorite) => {
        const list = sidebarList();
        if (!list || !favorite) {
            return;
        }

        favoriteElement(list, "data-favorite-sidebar-path", favorite.path)?.remove();
        document.querySelector("[data-sidebar-favorites-empty]")?.remove();

        const link = document.createElement("a");
        link.href = favoriteUrl(favorite);
        link.title = favorite.path;
        link.classList.toggle("is-hidden-item", Boolean(favorite.hidden));
        link.dataset.favoriteSidebarPath = favorite.path;
        link.dataset.favoriteDirectOpenUrl = favorite.directOpenUrl || favorite.openUrl || link.href;
        link.dataset.favoriteDetailUrl = favorite.detailUrl || favorite.openUrl || link.href;
        link.dataset.favoriteBookmarkLink = String((favorite.path || "").startsWith("bookmark:") && Boolean(favorite.directOpenUrl));
        if (favorite.openInNewTab) {
            link.target = "_blank";
            link.rel = "noopener noreferrer";
        }
        link.innerHTML = `
            <i aria-hidden="true"></i>
            <span></span>
        `;
        link.querySelector("i").className = favorite.iconClass;
        link.querySelector("span").textContent = favorite.name;
        list.append(link);
    };

    const removeSidebarFavorite = (path) => {
        const list = sidebarList();
        if (!list) {
            return;
        }

        const link = favoriteElement(list, "data-favorite-sidebar-path", path);
        if (link === activeSidebarFavorite) {
            closeSidebarFavoriteMenu();
        }
        link?.remove();

        if (!list.querySelector("a") && !document.querySelector("[data-sidebar-favorites-empty]")) {
            const empty = document.createElement("p");
            empty.dataset.sidebarFavoritesEmpty = "";
            empty.textContent = "No favorites yet.";
            list.after(empty);
        }
    };

    const removeManagementRow = (path) => {
        favoriteElement(document, "data-favorite-row-path", path)?.remove();
        const tableBody = document.querySelector(".favorites-panel tbody");
        if (!tableBody || tableBody.querySelector("[data-favorite-row-path]")) {
            return;
        }

        const emptyRow = document.createElement("tr");
        emptyRow.className = "empty-row";
        emptyRow.innerHTML = '<td colspan="5" class="empty">No favorites yet.</td>';
        tableBody.append(emptyRow);
    };

    const moveElement = (element, direction, candidates) => {
        const index = candidates.indexOf(element);
        const sibling = direction === "up" ? candidates[index - 1] : candidates[index + 1];
        if (!element || index < 0 || !sibling) {
            return;
        }
        if (direction === "up") {
            sibling.before(element);
        } else {
            sibling.after(element);
        }
    };

    const reorderSidebarFavorite = (path, direction) => {
        const link = favoriteElement(document, "data-favorite-sidebar-path", path);
        moveElement(link, direction, sidebarFavoriteLinks());
    };

    const managementRows = () =>
        Array.from(document.querySelectorAll("[data-favorite-row-path]"));

    const syncManagementMoveButtons = () => {
        const rows = managementRows();
        rows.forEach((row, index) => {
            row.querySelectorAll('form[data-favorite-action="move"]').forEach((form) => {
                const direction = form.querySelector('input[name="direction"]')?.value;
                const button = form.querySelector("button");
                if (button) {
                    button.disabled = direction === "up" ? index === 0 : index === rows.length - 1;
                }
            });
        });
    };

    const reorderManagementFavorite = (path, direction) => {
        const row = favoriteElement(document, "data-favorite-row-path", path);
        moveElement(row, direction, managementRows());
        syncManagementMoveButtons();
    };

    const handleMoveResponse = (form, body) => {
        const formData = new FormData(form);
        const path = form.dataset.favoritePath || formData.get("path") || "";
        const direction = formData.get("direction") || "";
        reorderSidebarFavorite(path, direction);
        reorderManagementFavorite(path, direction);
        showNotification(body.notification);
    };

    const handleFavoriteResponse = (form, body) => {
        const path = body.path || form.dataset.favoritePath || new FormData(form).get("path");
        showNotification(body.notification);
        updateMatchingButtons(path, body.active);

        if (body.active) {
            addSidebarFavorite(body.favorite);
            return;
        }

        removeSidebarFavorite(path);
        if (form.dataset.favoriteAction === "remove") {
            removeManagementRow(path);
        }
    };

    const togglePath = async (path) => {
        const formData = formDataWithCsrf();
        formData.append("path", path);
        const body = await requestJson("/api/v1/favorites/toggle", {
            method: "POST",
            body: formData
        });
        handleFavoriteResponse({
            dataset: {
                favoriteAction: "toggle",
                favoritePath: path
            }
        }, body);
        return body;
    };

    const toggleBookmark = async (id) => {
        const path = `bookmark:${id}`;
        const formData = formDataWithCsrf();
        formData.append("id", id);
        const body = await requestJson("/api/v1/favorites/toggle-bookmark", {
            method: "POST",
            body: formData
        });
        handleFavoriteResponse({
            dataset: {
                favoriteAction: "toggle",
                favoritePath: path
            }
        }, body);
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

        const formData = formDataWithCsrf();
        formData.append("path", link.dataset.favoriteSidebarPath || "");
        formData.append("direction", direction);
        const body = await requestJson("/api/v1/favorites/move", {
            method: "POST",
            body: formData
        });

        reorderSidebarFavorite(link.dataset.favoriteSidebarPath || "", direction);
        reorderManagementFavorite(link.dataset.favoriteSidebarPath || "", direction);
        showNotification(body.notification);
    };

    const removeSidebarFavoriteViaMenu = async (link) => {
        const path = link.dataset.favoriteSidebarPath || "";
        const formData = formDataWithCsrf();
        formData.append("path", path);
        const body = await requestJson("/api/v1/favorites/remove", {
            method: "POST",
            body: formData
        });
        handleFavoriteResponse({
            dataset: {
                favoriteAction: "remove",
                favoritePath: path
            }
        }, body);
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
                    window.location.href = link.dataset.favoriteDetailUrl || link.href;
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
                    window.location.href = link.href;
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

    const bindFavoriteForms = (root = document) => {
        root.querySelectorAll("form[data-favorite-action]").forEach((form) => {
            if (form.dataset.favoriteBound === "true") {
                return;
            }
            form.dataset.favoriteBound = "true";

            form.addEventListener("submit", async (event) => {
                event.preventDefault();
                const button = form.querySelector("button");
                button.disabled = true;
                try {
                    const body = await submitJsonForm(form);
                    if (form.dataset.favoriteAction === "move") {
                        handleMoveResponse(form, body);
                    } else {
                        handleFavoriteResponse(form, body);
                    }
                } catch (error) {
                    showToast("error", error.message || "Favorite update failed.");
                } finally {
                    button.disabled = false;
                }
            });
        });
    };

    bindFavoriteForms();
    syncManagementMoveButtons();
    bindSidebarFavoriteContextMenu();
    document.addEventListener("endervault:listing-refreshed", () => bindFavoriteForms());

    window.EnderVaultFavorites = {
        togglePath,
        toggleBookmark,
        bind: bindFavoriteForms
    };
});
