document.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll("[data-comic-page-url]").forEach((viewer) => {
        initializeComicViewer(viewer);
    });
});

const initializeComicViewer = (viewer) => {
    const image = viewer.querySelector("[data-comic-image]");
    const total = Number.parseInt(viewer.dataset.comicTotal || "0", 10);
    let current = Number.parseInt(viewer.dataset.comicCurrent || "0", 10);
    if (!image || !Number.isFinite(total) || total <= 0) {
        return;
    }

    const status = viewer.querySelector(".comic-status");
    const input = viewer.querySelector(".comic-page-input");
    const pageForm = viewer.querySelector(".comic-page-form");
    const pageSubmit = viewer.querySelector("[data-comic-page-submit]");
    const fullscreenButton = viewer.querySelector("[data-comic-fullscreen]");
    const actionButtons = Array.from(viewer.querySelectorAll("[data-comic-action]"));
    if (pageSubmit) {
        pageSubmit.hidden = true;
    }

    const pageUrl = (pageIndex) => {
        const url = new URL(viewer.dataset.comicPageUrl, window.location.origin);
        url.searchParams.set("page", String(pageIndex));
        return url.toString();
    };

    const detailUrl = (pageIndex) => {
        const url = new URL(window.location.href);
        url.searchParams.set("comicPage", String(pageIndex + 1));
        return url.toString();
    };

    const setButtonState = (button, disabled, targetPage) => {
        button.classList.toggle("is-disabled", disabled);
        button.setAttribute("aria-disabled", disabled ? "true" : "false");
        button.href = detailUrl(targetPage);
    };

    const updateControls = () => {
        const pageNumber = current + 1;
        if (status) {
            status.textContent = `Page ${pageNumber} / ${total}`;
        }
        if (input) {
            input.value = String(pageNumber);
        }

        actionButtons.forEach((button) => {
            const action = button.dataset.comicAction;
            if (action === "first") {
                setButtonState(button, current === 0, 0);
            } else if (action === "previous") {
                setButtonState(button, current === 0, Math.max(0, current - 1));
            } else if (action === "next") {
                setButtonState(button, current >= total - 1, Math.min(total - 1, current + 1));
            } else if (action === "last") {
                setButtonState(button, current >= total - 1, total - 1);
            }
        });
    };

    const preloadAround = () => {
        [current - 1, current + 1]
            .filter((pageIndex) => pageIndex >= 0 && pageIndex < total)
            .forEach((pageIndex) => {
                const preload = new Image();
                preload.src = pageUrl(pageIndex);
            });
    };

    const showPage = (pageIndex) => {
        const nextPage = Math.max(0, Math.min(total - 1, pageIndex));
        if (nextPage === current && image.src) {
            return;
        }
        current = nextPage;
        viewer.dataset.comicCurrent = String(current);
        image.src = pageUrl(current);
        image.alt = `${document.title || "Comic"} page ${current + 1}`;
        updateControls();
        window.history.replaceState(null, "", detailUrl(current));
        preloadAround();
    };

    const showRequestedPage = () => {
        if (!input) {
            return;
        }
        const requestedPage = Number.parseInt(input.value, 10);
        if (!Number.isFinite(requestedPage)) {
            input.value = String(current + 1);
            return;
        }
        showPage(requestedPage - 1);
    };

    actionButtons.forEach((button) => {
        button.addEventListener("click", (event) => {
            event.preventDefault();
            if (button.classList.contains("is-disabled")) {
                return;
            }

            const action = button.dataset.comicAction;
            if (action === "first") {
                showPage(0);
            } else if (action === "previous") {
                showPage(current - 1);
            } else if (action === "next") {
                showPage(current + 1);
            } else if (action === "last") {
                showPage(total - 1);
            }
        });
    });

    if (pageForm && input) {
        pageForm.addEventListener("submit", (event) => {
            event.preventDefault();
            showRequestedPage();
        });
        input.addEventListener("change", () => {
            showRequestedPage();
        });
    }

    if (fullscreenButton) {
        fullscreenButton.addEventListener("click", () => {
            setComicFullscreen(viewer, fullscreenButton, !viewer.classList.contains("is-comic-fullscreen"));
        });
    }

    document.addEventListener("keydown", (event) => {
        if (event.target && ["INPUT", "TEXTAREA", "SELECT"].includes(event.target.tagName)) {
            return;
        }
        if (event.key === "Escape" && viewer.classList.contains("is-comic-fullscreen")) {
            setComicFullscreen(viewer, fullscreenButton, false);
        } else if (event.key === "ArrowLeft" && viewer.classList.contains("is-comic-fullscreen")) {
            showPage(current - 1);
        } else if (event.key === "ArrowRight" && viewer.classList.contains("is-comic-fullscreen")) {
            showPage(current + 1);
        }
    });

    updateControls();
    preloadAround();
};

const setComicFullscreen = (viewer, button, enabled) => {
    viewer.classList.toggle("is-comic-fullscreen", enabled);
    document.body.classList.toggle("is-comic-viewer-fullscreen", enabled);
    if (!button) {
        return;
    }
    button.classList.toggle("is-active", enabled);
    button.setAttribute("aria-pressed", enabled ? "true" : "false");
    button.title = enabled ? "Exit fullscreen viewer" : "Toggle fullscreen viewer";
    button.setAttribute("aria-label", button.title);
    button.innerHTML = enabled
        ? '<i class="fas fa-compress" aria-hidden="true"></i>'
        : '<i class="fas fa-expand" aria-hidden="true"></i>';
};
