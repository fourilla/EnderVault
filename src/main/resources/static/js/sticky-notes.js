(function () {
    const metaValue = (name) => document.querySelector(`meta[name="${name}"]`)?.content || "";
    const context = {
        targetType: metaValue("endervault-sticky-target-type"),
        targetKey: metaValue("endervault-sticky-target-key"),
        surface: metaValue("endervault-sticky-surface"),
        label: metaValue("endervault-sticky-label")
    };

    if (!context.targetType || !context.surface) {
        return;
    }

    const apiRoot = "/api/v1/sticky-notes";
    const hiddenPreferenceKey = "endervault.stickyNotes.hidden";
    const controls = document.querySelector("[data-sticky-note-controls]");
    const appMain = document.querySelector(".app-main") || document.body;
    const states = new Map();
    const minimumWidth = 220;
    const minimumHeight = 140;
    const maximumWidth = 600;
    const maximumHeight = 700;
    let topLayer = 1;
    let layer;
    let activePointerInteractions = 0;

    const csrfHeaders = () => {
        const csrf = window.EnderVault?.csrfPair();
        return csrf ? { "X-CSRF-TOKEN": csrf.value } : {};
    };

    const jsonHeaders = () => ({
        "Content-Type": "application/json",
        ...csrfHeaders()
    });

    const clamp = (value, minimum, maximum) => Math.max(minimum, Math.min(maximum, value));
    const localBackupKey = (id) => `endervault.stickyNote.unsaved:${id}`;

    const showToast = (type, message) => window.EnderVault?.showToast(type, message);

    const requestDelete = (id) => window.EnderVault.requestJson(`${apiRoot}/${encodeURIComponent(id)}`, {
        method: "DELETE",
        headers: csrfHeaders()
    });

    const snapshot = (state) => ({
        content: state.editor.value,
        x: state.x,
        y: state.y,
        width: Math.round(state.card.offsetWidth),
        height: state.collapsed ? state.expandedHeight : Math.round(state.card.offsetHeight),
        collapsed: state.collapsed,
        layer: state.layer
    });

    const rememberLocally = (state) => {
        try {
            localStorage.setItem(localBackupKey(state.id), JSON.stringify({
                content: state.editor.value,
                savedAt: Date.now()
            }));
        } catch (error) {
            // Server autosave remains the primary persistence path.
        }
    };

    const clearLocalBackup = (state) => {
        try {
            localStorage.removeItem(localBackupKey(state.id));
        } catch (error) {
            // A stale local backup is harmless and can be overwritten later.
        }
    };

    const restoreLocalBackup = (state) => {
        try {
            const raw = localStorage.getItem(localBackupKey(state.id));
            if (!raw) {
                return;
            }
            const backup = JSON.parse(raw);
            if (typeof backup.content === "string" && backup.content !== state.editor.value) {
                state.editor.value = backup.content;
                state.dirty = true;
                showToast("warning", "A locally retained sticky note edit was restored and will be saved.");
            }
        } catch (error) {
            clearLocalBackup(state);
        }
    };

    const setStatus = (state, text) => {
        state.status.textContent = text;
    };

    const flush = async (state) => {
        if (!state.dirty) {
            return;
        }
        if (state.saving) {
            state.pending = true;
            return;
        }
        window.clearTimeout(state.debounceTimer);
        window.clearTimeout(state.maxTimer);
        state.debounceTimer = null;
        state.maxTimer = null;
        state.saving = true;
        state.dirty = false;
        setStatus(state, "Saving...");
        try {
            const body = await window.EnderVault.requestJson(`${apiRoot}/${encodeURIComponent(state.id)}`, {
                method: "PUT",
                headers: jsonHeaders(),
                body: JSON.stringify(snapshot(state))
            });
            if (body.note) {
                state.revision = body.note.revision;
            }
            clearLocalBackup(state);
            setStatus(state, "Saved");
        } catch (error) {
            state.dirty = true;
            rememberLocally(state);
            setStatus(state, "Not saved");
            if (!state.errorShown) {
                showToast("error", error.message || "Sticky note could not be saved.");
                state.errorShown = true;
            }
        } finally {
            state.saving = false;
            if (state.pending) {
                state.pending = false;
                void flush(state);
            }
        }
    };

    const scheduleSave = (state) => {
        state.dirty = true;
        state.errorShown = false;
        rememberLocally(state);
        setStatus(state, "Unsaved");
        window.clearTimeout(state.debounceTimer);
        state.debounceTimer = window.setTimeout(() => void flush(state), 1000);
        if (!state.maxTimer) {
            state.maxTimer = window.setTimeout(() => void flush(state), 10_000);
        }
    };

    const appMainLeft = () => appMain.getBoundingClientRect().left;

    const applyPosition = (state) => {
        const maxX = Math.max(0, window.innerWidth - appMainLeft() - state.card.offsetWidth - 8);
        const maxY = Math.max(0, window.innerHeight - state.card.offsetHeight - 8);
        state.x = clamp(state.x, 0, maxX);
        state.y = clamp(state.y, 0, maxY);
        state.card.style.left = `${appMainLeft() + state.x}px`;
        state.card.style.top = `${state.y}px`;
    };

    const beginPointerInteraction = (state) => {
        state.card.classList.add("is-pointer-interacting");
        activePointerInteractions += 1;
        layer.classList.add("is-interacting");
    };

    const endPointerInteraction = (state) => {
        state.card.classList.remove("is-pointer-interacting");
        activePointerInteractions = Math.max(0, activePointerInteractions - 1);
        if (activePointerInteractions === 0) {
            layer.classList.remove("is-interacting");
        }
    };

    const bringToFront = (state, persist = true) => {
        if (state.layer === topLayer) {
            return;
        }
        topLayer += 1;
        state.layer = topLayer;
        state.card.style.zIndex = String(state.layer);
        if (persist) {
            scheduleSave(state);
        }
    };

    const setCollapsed = (state, collapsed, persist = true) => {
        if (collapsed === state.collapsed) {
            return;
        }
        if (collapsed) {
            state.expandedHeight = Math.max(140, state.card.offsetHeight);
        }
        state.collapsed = collapsed;
        state.card.classList.toggle("is-collapsed", collapsed);
        state.collapseIcon.className = collapsed ? "fas fa-chevron-down" : "fas fa-chevron-up";
        state.collapseButton.title = collapsed ? "Expand sticky note" : "Collapse sticky note";
        state.collapseButton.setAttribute("aria-label", state.collapseButton.title);
        if (!collapsed) {
            state.card.style.height = `${state.expandedHeight}px`;
        }
        applyPosition(state);
        if (persist) {
            scheduleSave(state);
        }
    };

    const deleteNote = async (state) => {
        const confirmed = await window.EnderVault.askConfirmation({
            title: "Delete sticky note",
            message: "Delete this sticky note? This cannot be undone.",
            confirmLabel: "Delete",
            danger: true
        });
        if (!confirmed) {
            return;
        }
        try {
            const body = await requestDelete(state.id);
            clearLocalBackup(state);
            state.resizeObserver?.disconnect();
            state.card.remove();
            states.delete(state.id);
            window.EnderVault.showNotification(body.notification);
        } catch (error) {
            showToast("error", error.message || "Sticky note could not be deleted.");
        }
    };

    const enableDragging = (state, handle) => {
        handle.addEventListener("pointerdown", (event) => {
            if (event.button !== 0 || event.target.closest("button")) {
                return;
            }
            bringToFront(state, false);
            const cardRect = state.card.getBoundingClientRect();
            const offsetX = event.clientX - cardRect.left;
            const offsetY = event.clientY - cardRect.top;
            const startX = event.clientX;
            const startY = event.clientY;
            let moved = false;
            handle.setPointerCapture(event.pointerId);
            beginPointerInteraction(state);

            const onMove = (moveEvent) => {
                if (!moved && Math.hypot(moveEvent.clientX - startX, moveEvent.clientY - startY) < 3) {
                    return;
                }
                moved = true;
                moveEvent.preventDefault();
                state.x = moveEvent.clientX - appMainLeft() - offsetX;
                state.y = moveEvent.clientY - offsetY;
                applyPosition(state);
            };
            const onEnd = () => {
                handle.removeEventListener("pointermove", onMove);
                handle.removeEventListener("pointerup", onEnd);
                handle.removeEventListener("pointercancel", onEnd);
                endPointerInteraction(state);
                if (moved) {
                    scheduleSave(state);
                }
            };
            handle.addEventListener("pointermove", onMove);
            handle.addEventListener("pointerup", onEnd);
            handle.addEventListener("pointercancel", onEnd);
        });
    };

    const enableResizing = (state, handle) => {
        handle.addEventListener("pointerdown", (event) => {
            if (event.button !== 0 || state.collapsed) {
                return;
            }
            event.preventDefault();
            event.stopPropagation();
            bringToFront(state, false);
            const cardRect = state.card.getBoundingClientRect();
            const startX = event.clientX;
            const startY = event.clientY;
            const startWidth = cardRect.width;
            const startHeight = cardRect.height;
            const maxWidth = Math.max(minimumWidth, Math.min(maximumWidth, window.innerWidth - cardRect.left - 8));
            const maxHeight = Math.max(minimumHeight, Math.min(maximumHeight, window.innerHeight - cardRect.top - 8));
            let resized = false;
            state.resizing = true;
            handle.setPointerCapture(event.pointerId);
            beginPointerInteraction(state);

            const onMove = (moveEvent) => {
                moveEvent.preventDefault();
                const width = clamp(startWidth + moveEvent.clientX - startX, minimumWidth, maxWidth);
                const height = clamp(startHeight + moveEvent.clientY - startY, minimumHeight, maxHeight);
                resized = resized || Math.round(width) !== Math.round(startWidth)
                        || Math.round(height) !== Math.round(startHeight);
                state.card.style.width = `${Math.round(width)}px`;
                state.card.style.height = `${Math.round(height)}px`;
                state.expandedHeight = Math.round(height);
                applyPosition(state);
            };
            const onEnd = () => {
                handle.removeEventListener("pointermove", onMove);
                handle.removeEventListener("pointerup", onEnd);
                handle.removeEventListener("pointercancel", onEnd);
                state.resizing = false;
                endPointerInteraction(state);
                if (resized) {
                    scheduleSave(state);
                }
            };
            handle.addEventListener("pointermove", onMove);
            handle.addEventListener("pointerup", onEnd);
            handle.addEventListener("pointercancel", onEnd);
        });
    };

    const iconButton = (iconClass, title) => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "ghost icon-button";
        button.title = title;
        button.setAttribute("aria-label", title);
        const icon = document.createElement("i");
        icon.className = iconClass;
        icon.setAttribute("aria-hidden", "true");
        button.append(icon);
        return { button, icon };
    };

    const renderNote = (note) => {
        const card = document.createElement("article");
        card.className = "sticky-note-card";
        card.dataset.stickyNoteId = note.id;
        card.style.width = `${note.width}px`;
        card.style.height = `${note.height}px`;
        card.style.zIndex = String(note.layer);

        const header = document.createElement("header");
        header.className = "sticky-note-header";
        const title = document.createElement("span");
        title.className = "sticky-note-title";
        title.textContent = context.label || "Sticky note";
        title.title = context.label || "Sticky note";
        const actions = document.createElement("div");
        actions.className = "sticky-note-actions";
        const collapse = iconButton("fas fa-chevron-up", "Collapse sticky note");
        const remove = iconButton("fas fa-xmark", "Delete sticky note");
        actions.append(collapse.button, remove.button);
        header.append(title, actions);

        const body = document.createElement("div");
        body.className = "sticky-note-body";
        const editor = document.createElement("textarea");
        editor.className = "sticky-note-editor";
        editor.value = note.content || "";
        editor.placeholder = "Write a note...";
        editor.spellcheck = false;
        editor.setAttribute("aria-label", `Sticky note for ${context.label || "this page"}`);
        editor.maxLength = 10_000;
        const status = document.createElement("span");
        status.className = "sticky-note-status";
        status.textContent = "Saved";
        body.append(editor, status);
        const resizeHandle = iconButton("fas fa-grip-lines", "Resize sticky note").button;
        resizeHandle.className = "sticky-note-resize-handle";
        resizeHandle.tabIndex = -1;
        resizeHandle.setAttribute("aria-hidden", "true");
        card.append(header, body, resizeHandle);
        layer.append(card);

        const state = {
            id: note.id,
            card,
            editor,
            status,
            collapseButton: collapse.button,
            collapseIcon: collapse.icon,
            x: note.x,
            y: note.y,
            expandedHeight: note.height,
            collapsed: false,
            layer: note.layer,
            revision: note.revision,
            dirty: false,
            saving: false,
            pending: false,
            debounceTimer: null,
            maxTimer: null,
            errorShown: false,
            resizing: false,
            resizeObserver: null,
            lastWidth: note.width,
            lastHeight: note.height
        };
        states.set(note.id, state);
        topLayer = Math.max(topLayer, note.layer);
        restoreLocalBackup(state);
        setCollapsed(state, note.collapsed, false);
        applyPosition(state);
        if (state.dirty) {
            scheduleSave(state);
        }

        editor.addEventListener("input", () => scheduleSave(state));
        card.addEventListener("pointerdown", () => bringToFront(state));
        collapse.button.addEventListener("click", () => setCollapsed(state, !state.collapsed));
        remove.button.addEventListener("click", () => void deleteNote(state));
        header.addEventListener("dblclick", (event) => {
            if (event.button !== 0 || event.target.closest("button")) {
                return;
            }
            event.preventDefault();
            setCollapsed(state, !state.collapsed);
        });
        enableDragging(state, header);
        enableResizing(state, resizeHandle);

        if (window.ResizeObserver) {
            state.resizeObserver = new ResizeObserver(() => {
                if (state.collapsed) {
                    return;
                }
                const width = Math.round(card.offsetWidth);
                const height = Math.round(card.offsetHeight);
                if (width === state.lastWidth && height === state.lastHeight) {
                    return;
                }
                state.lastWidth = width;
                state.lastHeight = height;
                state.expandedHeight = height;
                applyPosition(state);
                if (!state.resizing) {
                    scheduleSave(state);
                }
            });
            state.resizeObserver.observe(card);
        }
    };

    const createNote = async (clientX = null, clientY = null) => {
        const offset = states.size * 24;
        const requestedX = Number.isFinite(clientX) ? clientX - appMainLeft() : 24 + offset;
        const requestedY = Number.isFinite(clientY) ? clientY : 84 + offset;
        try {
            const body = await window.EnderVault.requestJson(apiRoot, {
                method: "POST",
                headers: jsonHeaders(),
                body: JSON.stringify({
                    targetType: context.targetType,
                    targetKey: context.targetKey,
                    surface: context.surface,
                    x: Math.max(0, Math.round(requestedX)),
                    y: Math.max(0, Math.round(requestedY))
                })
            });
            setAllHidden(false);
            renderNote(body.note);
            states.get(body.note.id)?.editor.focus();
            window.EnderVault.showNotification(body.notification);
        } catch (error) {
            showToast("error", error.message || "Sticky note could not be created.");
        }
    };

    const setAllHidden = (hidden) => {
        layer.hidden = hidden;
        const button = controls?.querySelector("[data-sticky-note-visibility]");
        const icon = button?.querySelector("i");
        const status = controls?.querySelector("[data-sticky-note-visibility-status]");
        if (button && icon) {
            button.title = hidden ? "Show sticky notes" : "Hide sticky notes";
            button.setAttribute("aria-label", button.title);
            button.setAttribute("aria-pressed", String(!hidden));
            button.classList.toggle("is-active", !hidden);
        }
        if (status) {
            status.textContent = hidden ? "Hidden" : "Visible";
            status.classList.toggle("is-active", !hidden);
        }
        try {
            localStorage.setItem(hiddenPreferenceKey, hidden ? "true" : "false");
        } catch (error) {
            // Visibility remains valid for the current page.
        }
    };

    const keepaliveFlush = (state) => {
        if (!state.dirty) {
            return;
        }
        const request = snapshot(state);
        rememberLocally(state);
        try {
            fetch(`${apiRoot}/${encodeURIComponent(state.id)}`, {
                method: "PUT",
                credentials: "same-origin",
                keepalive: true,
                headers: {
                    "Accept": "application/json",
                    "X-Requested-With": "fetch",
                    ...jsonHeaders()
                },
                body: JSON.stringify(request)
            });
        } catch (error) {
            // The local backup remains available for the next visit.
        }
    };

    const initialize = async () => {
        if (!window.EnderVault || !controls) {
            return;
        }
        controls.hidden = false;
        layer = document.createElement("div");
        layer.className = "sticky-note-layer";
        layer.setAttribute("aria-label", "Sticky notes");
        document.body.append(layer);

        controls.querySelector("[data-sticky-note-add]")?.addEventListener("click", () => void createNote());
        controls.querySelector("[data-sticky-note-visibility]")?.addEventListener("click", () => setAllHidden(!layer.hidden));
        document.querySelectorAll("[data-sticky-note-manager-delete]").forEach((button) => {
            button.addEventListener("click", async () => {
                const confirmed = await window.EnderVault.askConfirmation({
                    title: "Delete sticky note",
                    message: "Delete this sticky note? This cannot be undone.",
                    confirmLabel: "Delete",
                    danger: true
                });
                if (!confirmed) {
                    return;
                }
                button.disabled = true;
                try {
                    const body = await requestDelete(button.dataset.stickyNoteManagerDelete);
                    button.closest("tr")?.remove();
                    const remaining = document.querySelectorAll("[data-sticky-note-manager-delete]").length;
                    const count = document.querySelector("[data-sticky-note-manager-count]");
                    if (count) {
                        count.textContent = `${remaining} note(s)`;
                    }
                    const empty = document.querySelector("[data-sticky-note-manager-empty]");
                    if (empty) {
                        empty.hidden = remaining > 0;
                    }
                    window.EnderVault.showNotification(body.notification);
                } catch (error) {
                    button.disabled = false;
                    showToast("error", error.message || "Sticky note could not be deleted.");
                }
            });
        });
        window.EnderVaultContextMenus?.registerGlobalAction({
            id: "new-sticky-note",
            group: "sticky-note",
            label: "New sticky note",
            icon: "fas fa-note-sticky",
            run: (menuContext) => createNote(menuContext?.event?.clientX, menuContext?.event?.clientY)
        });

        let initiallyHidden = false;
        try {
            initiallyHidden = localStorage.getItem(hiddenPreferenceKey) === "true";
        } catch (error) {
            initiallyHidden = false;
        }
        setAllHidden(initiallyHidden);

        try {
            const query = new URLSearchParams({
                targetType: context.targetType,
                targetKey: context.targetKey,
                surface: context.surface
            });
            const body = await window.EnderVault.requestJson(`${apiRoot}?${query}`);
            body.notes.forEach(renderNote);
        } catch (error) {
            showToast("error", error.message || "Sticky notes could not be loaded.");
        }
        window.addEventListener("resize", () => states.forEach(applyPosition));
        window.addEventListener("pagehide", () => states.forEach(keepaliveFlush));
        document.addEventListener("visibilitychange", () => {
            if (document.visibilityState === "hidden") {
                states.forEach(keepaliveFlush);
            }
        });
    };

    document.addEventListener("DOMContentLoaded", () => void initialize());
})();
