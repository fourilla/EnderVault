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
        link.dataset.favoriteSidebarPath = favorite.path;
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
        const body = await requestJson("/files/favorites/toggle", {
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
        const body = await requestJson("/files/favorites/move", {
            method: "POST",
            body: formData
        });

        if (direction === "up") {
            sibling.before(link);
        } else {
            sibling.after(link);
        }
        showNotification(body.notification);
    };

    const removeSidebarFavoriteViaMenu = async (link) => {
        const path = link.dataset.favoriteSidebarPath || "";
        const formData = formDataWithCsrf();
        formData.append("path", path);
        const body = await requestJson("/files/favorites/remove", {
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

        menu.append(sidebarMenuButton({
            icon: "fas fa-arrow-up-right-from-square",
            label: "Open",
            action: () => {
                window.location.href = link.href;
            }
        }));

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
                    handleFavoriteResponse(form, body);
                } catch (error) {
                    showToast("error", error.message || "Favorite update failed.");
                } finally {
                    button.disabled = false;
                }
            });
        });
    };

    bindFavoriteForms();
    bindSidebarFavoriteContextMenu();
    document.addEventListener("endervault:listing-refreshed", () => bindFavoriteForms());

    window.EnderVaultFavorites = {
        togglePath,
        bind: bindFavoriteForms
    };
});
