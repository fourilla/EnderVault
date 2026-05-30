document.addEventListener("DOMContentLoaded", () => {
    const deleteSelectedButton = document.getElementById("deleteSelectedButton");
    const selectionButtons = [
        document.getElementById("downloadSelectedButton"),
        deleteSelectedButton
    ].filter(Boolean);
    const selectableItemSelector = ".browser-card, tbody tr";
    const longPressDelayMs = 520;
    const longPressMoveTolerance = 10;
    const selectionState = {
        active: false,
        longPressTimer: null,
        longPressItem: null,
        pointerId: null,
        pointerStartX: 0,
        pointerStartY: 0,
        suppressNextClick: false
    };

    document.body.classList.add("js-selection-enhanced");

    const selectedItemCheckboxes = () =>
        Array.from(document.querySelectorAll('input[name="items"][form="bulkActionForm"]'));

    const selectAllCheckboxes = () =>
        Array.from(document.querySelectorAll("[data-select-all]"));

    const selectPickLabels = () =>
        Array.from(document.querySelectorAll("[data-select-pick-label]"));

    const checkboxForItem = (item) =>
        item?.querySelector('input[name="items"][form="bulkActionForm"]') || null;

    const selectableItemFromTarget = (target) => {
        const item = target.closest(selectableItemSelector);
        return checkboxForItem(item) ? item : null;
    };

    const primaryLinkForItem = (item) => item.querySelector("a[href]");

    const isNativeControlTarget = (target) =>
        Boolean(target.closest("button, input, label, select, textarea, summary"));

    const isSelectionControlTarget = (target) =>
        Boolean(target.closest('input[name="items"][form="bulkActionForm"], [data-select-all], .select-all-label'));

    const setSelectionMode = (active) => {
        selectionState.active = active;
        document.body.classList.toggle("selection-mode-active", active);
    };

    const clearSelection = () => {
        selectedItemCheckboxes().forEach((checkbox) => {
            checkbox.checked = false;
        });
    };

    const syncSelectableItemState = () => {
        selectedItemCheckboxes().forEach((checkbox) => {
            const item = checkbox.closest(selectableItemSelector);
            if (!item) {
                return;
            }
            item.classList.toggle("is-selected", checkbox.checked);
            item.setAttribute("aria-selected", checkbox.checked ? "true" : "false");
        });
    };

    const updateSelectionActions = () => {
        const checkboxes = selectedItemCheckboxes();
        const selectedCount = checkboxes.filter((checkbox) => checkbox.checked).length;
        const hasSelection = selectedCount > 0;

        syncSelectableItemState();
        setSelectionMode(hasSelection);

        selectPickLabels().forEach((label) => {
            label.hidden = true;
        });

        selectAllCheckboxes().forEach((checkbox) => {
            checkbox.disabled = checkboxes.length === 0;
            checkbox.checked = checkboxes.length > 0 && selectedCount === checkboxes.length;
            checkbox.indeterminate = selectedCount > 0 && selectedCount < checkboxes.length;
        });

        selectionButtons.forEach((button) => {
            button.disabled = !hasSelection;
            button.title = hasSelection ? button.dataset.readyTitle : "Select items first";
        });
    };

    const toggleItemSelection = (item) => {
        const checkbox = checkboxForItem(item);
        if (!checkbox) {
            return;
        }

        checkbox.checked = !checkbox.checked;
        updateSelectionActions();
    };

    const exitSelectionMode = () => {
        clearSelection();
        updateSelectionActions();
    };

    const cancelLongPress = () => {
        window.clearTimeout(selectionState.longPressTimer);
        selectionState.longPressTimer = null;
        selectionState.longPressItem = null;
        selectionState.pointerId = null;
    };

    document.addEventListener("pointerdown", (event) => {
        const item = selectableItemFromTarget(event.target);
        if (!item || event.button !== 0 || isSelectionControlTarget(event.target)) {
            return;
        }

        if (event.target.closest(".table-actions, .action-icon")) {
            return;
        }

        cancelLongPress();
        selectionState.longPressItem = item;
        selectionState.pointerId = event.pointerId;
        selectionState.pointerStartX = event.clientX;
        selectionState.pointerStartY = event.clientY;
        selectionState.longPressTimer = window.setTimeout(() => {
            selectionState.suppressNextClick = true;
            toggleItemSelection(item);
            cancelLongPress();
        }, longPressDelayMs);
    });

    document.addEventListener("pointermove", (event) => {
        if (!selectionState.longPressTimer || selectionState.pointerId !== event.pointerId) {
            return;
        }

        const moveX = Math.abs(event.clientX - selectionState.pointerStartX);
        const moveY = Math.abs(event.clientY - selectionState.pointerStartY);
        if (moveX > longPressMoveTolerance || moveY > longPressMoveTolerance) {
            cancelLongPress();
        }
    });

    document.addEventListener("pointerup", cancelLongPress);
    document.addEventListener("pointercancel", cancelLongPress);
    document.addEventListener("contextmenu", (event) => {
        if (selectionState.suppressNextClick && selectableItemFromTarget(event.target)) {
            event.preventDefault();
        }
    });

    document.addEventListener("click", (event) => {
        const item = selectableItemFromTarget(event.target);

        if (selectionState.suppressNextClick) {
            event.preventDefault();
            event.stopPropagation();
            selectionState.suppressNextClick = false;
            return;
        }

        if (item) {
            if (event.target.closest(".select-cell") && !event.target.matches('input[name="items"][form="bulkActionForm"]')) {
                event.preventDefault();
                toggleItemSelection(item);
                return;
            }

            if (isSelectionControlTarget(event.target)) {
                return;
            }

            const selectionClick = selectionState.active || event.ctrlKey || event.metaKey;
            if (selectionClick) {
                event.preventDefault();
                event.stopPropagation();
                toggleItemSelection(item);
                return;
            }

            if (event.target.closest("a[href]")) {
                return;
            }

            if (isNativeControlTarget(event.target)) {
                return;
            }

            const primaryLink = primaryLinkForItem(item);
            if (primaryLink) {
                event.preventDefault();
                window.location.href = primaryLink.href;
            }
            return;
        }

        if (selectionState.active && !event.target.closest(".toolbar, .upload-activity, .toast-region")) {
            exitSelectionMode();
        }
    });

    document.addEventListener("change", (event) => {
        if (event.target.matches("[data-select-all]")) {
            selectedItemCheckboxes().forEach((checkbox) => {
                checkbox.checked = event.target.checked;
            });
            updateSelectionActions();
            return;
        }

        if (event.target.matches('input[name="items"][form="bulkActionForm"]')) {
            updateSelectionActions();
        }
    });

    document.addEventListener("endervault:listing-refreshed", updateSelectionActions);

    selectionButtons.forEach((button) => {
        button.dataset.readyTitle = button.title;
    });
    updateSelectionActions();

    window.EnderVaultFileSelection = {
        update: updateSelectionActions,
        clear: exitSelectionMode,
        selectedItemCheckboxes
    };
});
