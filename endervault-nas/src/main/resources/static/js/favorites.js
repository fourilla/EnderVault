document.addEventListener("DOMContentLoaded", () => {
    const favoriteForms = document.querySelectorAll("form[data-favorite-action]");
    if (!favoriteForms.length) {
        return;
    }

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
    };

    const favoriteElement = (root, attribute, path) =>
        Array.from(root.querySelectorAll(`[${attribute}]`))
                .find((element) => element.getAttribute(attribute) === path);

    const addSidebarFavorite = (favorite) => {
        const list = document.querySelector("[data-sidebar-favorites-list]");
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
        const list = document.querySelector("[data-sidebar-favorites-list]");
        if (!list) {
            return;
        }

        favoriteElement(list, "data-favorite-sidebar-path", path)?.remove();
        if (!list.querySelector("a")) {
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
        window.EnderVault.showNotification(body.notification);
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

    favoriteForms.forEach((form) => {
        if (form.dataset.favoriteBound === "true") {
            return;
        }
        form.dataset.favoriteBound = "true";

        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            const button = form.querySelector("button");
            button.disabled = true;
            try {
                const body = await window.EnderVault.submitJsonForm(form);
                handleFavoriteResponse(form, body);
            } catch (error) {
                window.EnderVault.showToast("error", error.message || "Favorite update failed.");
            } finally {
                button.disabled = false;
            }
        });
    });
});
