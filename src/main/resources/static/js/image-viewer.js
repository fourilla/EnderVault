const initializeImageViewers = (scope = document) => {
    const roots = scope.matches?.("[data-image-viewer]")
        ? [scope]
        : scope.querySelectorAll("[data-image-viewer]");
    roots.forEach((root) => {
        if (root.dataset.imageViewerBound === "true") {
            return;
        }
        root.dataset.imageViewerBound = "true";
        const source = root.querySelector("[data-image-viewer-source]");
        const dimensions = root.querySelector("[data-image-dimensions]");
        const zoomStatus = root.querySelector("[data-image-zoom]");
        const buttons = Array.from(root.querySelectorAll("[data-image-action]"));

        if (!source || !dimensions || !zoomStatus) {
            return;
        }

        let viewer;
        let flippedHorizontally = false;
        let flippedVertically = false;
        const fullscreenButton = root.querySelector("[data-image-action='fullscreen']");

        const setButtonsEnabled = (enabled) => {
            buttons.forEach((button) => {
                button.disabled = !enabled;
            });
        };

        const updateDimensions = () => {
            if (source.naturalWidth > 0 && source.naturalHeight > 0) {
                dimensions.textContent = `${source.naturalWidth} x ${source.naturalHeight}`;
                return;
            }
            dimensions.textContent = "Image dimensions unavailable";
        };

        const updateZoom = (ratio) => {
            if (!Number.isFinite(ratio) || ratio <= 0) {
                return;
            }
            zoomStatus.textContent = `${Math.round(ratio * 100)}%`;
        };

        const handleLoadFailure = () => {
            root.classList.add("is-load-failed");
            dimensions.textContent = "Image could not be loaded";
            zoomStatus.textContent = "Unavailable";
            setButtonsEnabled(false);
        };

        source.addEventListener("load", updateDimensions);
        source.addEventListener("error", handleLoadFailure);

        if (typeof window.Viewer !== "function") {
            updateDimensions();
            zoomStatus.textContent = "Basic preview";
            return;
        }

        source.addEventListener("ready", () => {
            root.classList.add("is-viewer-ready");
            setButtonsEnabled(true);
            updateDimensions();
        });

        source.addEventListener("viewed", (event) => {
            updateZoom(event.detail?.image?.ratio ?? viewer?.imageData?.ratio);
        });

        source.addEventListener("zoom", (event) => {
            updateZoom(event.detail?.ratio);
        });

        try {
            viewer = new window.Viewer(source, {
                inline: true,
                button: false,
                navbar: false,
                title: false,
                toolbar: false,
                tooltip: true,
                movable: true,
                zoomable: true,
                rotatable: true,
                scalable: true,
                transition: true,
                keyboard: true,
                toggleOnDblclick: true,
                initialCoverage: 0.9,
                minZoomRatio: 0.05,
                maxZoomRatio: 20,
                zoomRatio: 0.15
            });
        } catch (error) {
            root.classList.add("is-load-failed");
            dimensions.textContent = "Enhanced image viewer unavailable";
            zoomStatus.textContent = "Fallback";
            console.warn("Viewer.js initialization failed.", error);
            return;
        }

        const setFullscreen = (enabled) => {
            root.classList.toggle("is-image-fullscreen", enabled);
            document.body.classList.toggle("is-image-viewer-fullscreen", enabled);
            if (fullscreenButton) {
                fullscreenButton.classList.toggle("is-active", enabled);
                fullscreenButton.setAttribute("aria-pressed", enabled ? "true" : "false");
                fullscreenButton.title = enabled ? "Exit fullscreen viewer" : "Open fullscreen viewer";
                fullscreenButton.setAttribute("aria-label", fullscreenButton.title);
                fullscreenButton.innerHTML = enabled
                    ? '<i class="fas fa-compress" aria-hidden="true"></i>'
                    : '<i class="fas fa-expand" aria-hidden="true"></i>';
            }
            window.setTimeout(() => window.dispatchEvent(new Event("resize")), 0);
        };

        const reset = () => {
            flippedHorizontally = false;
            flippedVertically = false;
            viewer.reset();
        };

        const actions = {
            "zoom-out": () => viewer.zoom(-0.15, true),
            "zoom-in": () => viewer.zoom(0.15, true),
            "one-to-one": () => viewer.zoomTo(1, true),
            reset,
            "rotate-left": () => viewer.rotate(-90),
            "rotate-right": () => viewer.rotate(90),
            "flip-horizontal": () => {
                flippedHorizontally = !flippedHorizontally;
                viewer.scaleX(flippedHorizontally ? -1 : 1);
            },
            "flip-vertical": () => {
                flippedVertically = !flippedVertically;
                viewer.scaleY(flippedVertically ? -1 : 1);
            },
            fullscreen: () => setFullscreen(!root.classList.contains("is-image-fullscreen"))
        };

        buttons.forEach((button) => {
            button.addEventListener("click", () => {
                actions[button.dataset.imageAction]?.();
            });
        });

        if (source.complete) {
            if (source.naturalWidth > 0) {
                updateDimensions();
            } else {
                handleLoadFailure();
            }
        }

        const handleKeydown = (event) => {
            if (event.key === "Escape" && root.classList.contains("is-image-fullscreen")) {
                setFullscreen(false);
            }
        };
        document.addEventListener("keydown", handleKeydown);
        root._endervaultImageViewerCleanup = () => {
            document.removeEventListener("keydown", handleKeydown);
            viewer?.destroy?.();
            document.body.classList.remove("is-image-viewer-fullscreen");
            delete root.dataset.imageViewerBound;
        };
    });
};

const destroyImageViewers = (scope = document) => {
    const roots = scope.matches?.("[data-image-viewer]")
        ? [scope]
        : scope.querySelectorAll("[data-image-viewer]");
    roots.forEach((root) => {
        root._endervaultImageViewerCleanup?.();
        delete root._endervaultImageViewerCleanup;
    });
};

window.EnderVaultImageViewers = {
    init: initializeImageViewers,
    destroy: destroyImageViewers
};

document.addEventListener("DOMContentLoaded", () => initializeImageViewers());
