import { NOTE_MARGIN, projectNotePosition, pagePlacement, defaultNotePlacement, edgeScrollDelta } from "./sticky-note-placement.js";

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
    let controls = null;
    let appMain = null;
    const states = new Map();
    const minimumWidth = 220;
    const minimumHeight = 140;
    const maximumWidth = 600;
    const maximumHeight = 700;
    let topLayer = 1;
    let layer;
    let activePointerInteractions = 0;
    let contextRevision = 0;
    let layoutObserver;
    let layoutFrame = null;
    let controlListeners = [];

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
        xRatio: state.xRatio,
        width: state.width,
        height: state.height,
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
            if (backup.content === state.editor.value) {
                clearLocalBackup(state);
                return;
            }
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
        if (!state.dirty || states.get(state.id) !== state) {
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
            if (states.get(state.id) !== state) {
                return;
            }
            if (body.note) {
                state.revision = body.note.revision;
            }
            if (!state.dirty) {
                clearLocalBackup(state);
            }
            setStatus(state, state.dirty ? "Unsaved" : "Saved");
        } catch (error) {
            if (states.get(state.id) !== state) {
                return;
            }
            state.dirty = true;
            rememberLocally(state);
            setStatus(state, "Not saved");
            if (!state.errorShown) {
                showToast("error", error.message || "Sticky note could not be saved.");
                state.errorShown = true;
            }
        } finally {
            state.saving = false;
            if (state.pending && states.get(state.id) === state) {
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

    const containerGeometry = () => {
        const rect = appMain.getBoundingClientRect();
        return { left: rect.left, top: rect.top, width: appMain.clientWidth, documentTop: rect.top + window.scrollY };
    };

    const updatePageExtent = () => {
        if (!appMain || !layer) {
            return;
        }
        const geometry = containerGeometry();
        let bottom = 0;
        if (!layer.hidden) {
            states.forEach((state) => {
                const position = projectNotePosition(state, geometry, state.card.offsetWidth);
                bottom = Math.max(bottom, position.y + state.card.offsetHeight + NOTE_MARGIN);
            });
        }
        const value = `${Math.ceil(bottom)}px`;
        if (appMain.style.getPropertyValue("--sticky-note-page-height") !== value) {
            appMain.style.setProperty("--sticky-note-page-height", value);
        }
    };

    const applyPosition = (state) => {
        const position = projectNotePosition(state, containerGeometry(), state.card.offsetWidth);
        state.card.style.left = `${position.x}px`;
        state.card.style.top = `${position.y}px`;
    };

    const applyLayout = () => {
        if (!layer) {
            return;
        }
        states.forEach(applyPosition);
        updatePageExtent();
    };

    const scheduleLayout = () => {
        if (!layer || layoutFrame !== null) {
            return;
        }
        layoutFrame = window.requestAnimationFrame(() => {
            layoutFrame = null;
            applyLayout();
        });
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
        state.collapsed = collapsed;
        state.card.classList.toggle("is-collapsed", collapsed);
        state.collapseIcon.className = collapsed ? "fas fa-chevron-down" : "fas fa-chevron-up";
        state.collapseButton.title = collapsed ? "Expand sticky note" : "Collapse sticky note";
        state.collapseButton.setAttribute("aria-label", state.collapseButton.title);
        if (!collapsed) {
            state.card.style.height = `${state.height}px`;
        }
        applyPosition(state);
        updatePageExtent();
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
        if (!confirmed || states.get(state.id) !== state) {
            return;
        }
        try {
            const body = await requestDelete(state.id);
            clearLocalBackup(state);
            document.dispatchEvent(new CustomEvent("endervault:sticky-note-deleted", {
                detail: { id: state.id }
            }));
            window.EnderVault.showNotification(body.notification);
        } catch (error) {
            showToast("error", error.message || "Sticky note could not be deleted.");
        }
    };

    const trackPointer = (state, handle, event, onMove, onEnd, autoScroll = false) => {
        let pointer = { clientX: event.clientX, clientY: event.clientY };
        let finished = false;
        let scrolling = false;
        let frame = null;
        let lastTime = null;

        const finish = (persist = true) => {
            if (finished) {
                return;
            }
            finished = true;
            window.cancelAnimationFrame(frame);
            handle.removeEventListener("pointermove", move);
            handle.removeEventListener("pointerup", ended);
            handle.removeEventListener("pointercancel", ended);
            handle.removeEventListener("lostpointercapture", ended);
            window.removeEventListener("blur", ended);
            document.removeEventListener("visibilitychange", visibilityChanged);
            state.endInteraction = null;
            if (handle.hasPointerCapture(event.pointerId)) {
                handle.releasePointerCapture(event.pointerId);
            }
            endPointerInteraction(state);
            onEnd(persist);
        };
        const ended = (endEvent) => {
            if (endEvent?.pointerId != null && endEvent.pointerId !== event.pointerId) {
                return;
            }
            finish();
        };
        const visibilityChanged = () => {
            if (document.visibilityState === "hidden") {
                finish();
            }
        };
        const tick = (time) => {
            frame = null;
            if (finished || document.querySelector("dialog:modal")) {
                finish();
                return;
            }
            const top = Math.max(0, document.querySelector(".app-topbar")?.getBoundingClientRect().bottom || 0);
            const delta = edgeScrollDelta(pointer.clientY, top, window.innerHeight, lastTime === null ? 16 : time - lastTime);
            lastTime = time;
            if (delta !== 0) {
                const previous = window.scrollY;
                window.scrollBy({ top: delta, behavior: "instant" });
                if (window.scrollY !== previous) {
                    onMove(pointer);
                }
            }
            frame = window.requestAnimationFrame(tick);
        };
        const move = (moveEvent) => {
            if (moveEvent.pointerId !== event.pointerId) {
                return;
            }
            moveEvent.preventDefault();
            pointer = { clientX: moveEvent.clientX, clientY: moveEvent.clientY };
            if (onMove(pointer) && autoScroll && !scrolling) {
                scrolling = true;
                frame = window.requestAnimationFrame(tick);
            }
        };

        state.endInteraction = finish;
        handle.setPointerCapture(event.pointerId);
        beginPointerInteraction(state);
        handle.addEventListener("pointermove", move);
        handle.addEventListener("pointerup", ended);
        handle.addEventListener("pointercancel", ended);
        handle.addEventListener("lostpointercapture", ended);
        window.addEventListener("blur", ended);
        document.addEventListener("visibilitychange", visibilityChanged);
    };

    const enableDragging = (state, handle) => {
        handle.addEventListener("pointerdown", (event) => {
            if (event.button !== 0 || event.target.closest("button") || state.endInteraction) {
                return;
            }
            bringToFront(state, false);
            const rect = state.card.getBoundingClientRect();
            const offsetX = event.clientX - rect.left;
            const offsetY = event.clientY - rect.top;
            let moved = false;
            trackPointer(state, handle, event, (pointer) => {
                if (!moved && Math.hypot(pointer.clientX - event.clientX, pointer.clientY - event.clientY) < 3) {
                    return false;
                }
                moved = true;
                const geometry = containerGeometry();
                Object.assign(state, pagePlacement(pointer.clientX - geometry.left - offsetX,
                        pointer.clientY - geometry.top - offsetY, geometry.width, state.card.offsetWidth,
                        state.xRatio ?? 0));
                applyLayout();
                return true;
            }, (persist) => {
                if (persist && moved) {
                    scheduleSave(state);
                }
            }, true);
        });
    };

    const enableResizing = (state, handle) => {
        handle.addEventListener("pointerdown", (event) => {
            if (event.button !== 0 || state.collapsed || state.endInteraction) {
                return;
            }
            event.preventDefault();
            event.stopPropagation();
            bringToFront(state, false);
            const rect = state.card.getBoundingClientRect();
            const initialGeometry = containerGeometry();
            const left = rect.left - initialGeometry.left;
            const top = rect.top - initialGeometry.top;
            let resized = false;
            trackPointer(state, handle, event, (pointer) => {
                const geometry = containerGeometry();
                const maxWidth = Math.max(minimumWidth, Math.min(maximumWidth, geometry.width - left - NOTE_MARGIN));
                const width = Math.round(clamp(rect.width + pointer.clientX - event.clientX, minimumWidth, maxWidth));
                const height = Math.round(clamp(rect.height + pointer.clientY - event.clientY, minimumHeight, maximumHeight));
                if (!resized && width === Math.round(rect.width) && height === Math.round(rect.height)) {
                    return false;
                }
                resized = true;
                state.width = width;
                state.height = height;
                state.card.style.width = `${width}px`;
                state.card.style.height = `${height}px`;
                Object.assign(state, pagePlacement(left, top, geometry.width, state.card.offsetWidth, state.xRatio ?? 0));
                applyLayout();
                return true;
            }, (persist) => {
                if (persist && resized) {
                    scheduleSave(state);
                }
            });
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
            xRatio: note.xRatio ?? null,
            width: note.width,
            height: note.height,
            collapsed: false,
            layer: note.layer,
            revision: note.revision,
            dirty: false,
            saving: false,
            pending: false,
            debounceTimer: null,
            maxTimer: null,
            errorShown: false,
            endInteraction: null
        };
        states.set(note.id, state);
        topLayer = Math.max(topLayer, note.layer);
        restoreLocalBackup(state);
        setCollapsed(state, note.collapsed, false);
        applyPosition(state);
        updatePageExtent();
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

        layoutObserver?.observe(card);
    };

    const createNote = async (clientX = null, clientY = null) => {
        if (!layer) {
            return;
        }
        const offset = states.size * 24;
        const geometry = containerGeometry();
        const topbarBottom = document.querySelector(".app-topbar")?.getBoundingClientRect().bottom || 0;
        const noteWidth = Math.min(280, Math.max(0, geometry.width - 2 * NOTE_MARGIN));
        const position = Number.isFinite(clientX) && Number.isFinite(clientY)
                ? pagePlacement(clientX - geometry.left, clientY - geometry.top, geometry.width, noteWidth)
                : defaultNotePlacement(geometry, { top: topbarBottom, bottom: window.innerHeight },
                        { width: noteWidth, height: 220 }, offset);
        const revision = contextRevision;
        try {
            const body = await window.EnderVault.requestJson(apiRoot, {
                method: "POST",
                headers: jsonHeaders(),
                body: JSON.stringify({
                    targetType: context.targetType,
                    targetKey: context.targetKey,
                    surface: context.surface,
                    ...position
                })
            });
            if (revision === contextRevision && layer) {
                setAllHidden(false);
                renderNote(body.note);
                states.get(body.note.id)?.editor.focus({ preventScroll: true });
            }
            window.EnderVault.showNotification(body.notification);
        } catch (error) {
            if (revision === contextRevision && layer) {
                showToast("error", error.message || "Sticky note could not be created.");
            }
        }
    };

    const setAllHidden = (hidden) => {
        if (!layer) {
            return;
        }
        if (hidden) {
            states.forEach((state) => state.endInteraction?.());
        }
        layer.hidden = hidden;
        updatePageExtent();
        const button = controls?.querySelector("[data-sticky-note-visibility]");
        const trigger = controls?.querySelector("[data-sticky-note-trigger]");
        const label = button?.querySelector("[data-sticky-note-visibility-label]");
        const icon = button?.querySelector("i");
        const status = controls?.querySelector("[data-sticky-note-visibility-status]");
        if (button && icon) {
            button.title = hidden ? "Show sticky notes" : "Hide sticky notes";
            button.setAttribute("aria-label", button.title);
            button.setAttribute("aria-pressed", String(!hidden));
            icon.className = hidden ? "fas fa-eye" : "fas fa-eye-slash";
            if (label) {
                label.textContent = button.title;
            }
        }
        if (trigger) {
            trigger.title = hidden ? "Sticky notes hidden" : "Sticky notes visible";
            trigger.setAttribute("aria-label", trigger.title);
            trigger.classList.toggle("is-active", !hidden);
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

    const normalizedContext = (candidate) => ({
        targetType: String(candidate?.targetType || "").trim(),
        targetKey: String(candidate?.targetKey || "").trim(),
        surface: String(candidate?.surface || "").trim(),
        label: String(candidate?.label || "").trim()
    });

    const clearRenderedNotes = () => {
        states.forEach((state) => {
            state.endInteraction?.();
            window.clearTimeout(state.debounceTimer);
            window.clearTimeout(state.maxTimer);
            keepaliveFlush(state);
            layoutObserver?.unobserve(state.card);
            state.card.remove();
        });
        states.clear();
        topLayer = 1;
        updatePageExtent();
    };

    const loadCurrentContext = async () => {
        const revision = ++contextRevision;
        const query = new URLSearchParams({
            targetType: context.targetType,
            targetKey: context.targetKey,
            surface: context.surface
        });
        let body;
        try {
            body = await window.EnderVault.requestJson(`${apiRoot}?${query}`);
        } catch (error) {
            if (revision === contextRevision && layer) {
                throw error;
            }
            return;
        }
        if (revision !== contextRevision || !layer) {
            return;
        }
        body.notes.forEach(renderNote);
    };

    const setContext = async (candidate) => {
        const next = normalizedContext(candidate);
        if (!next.targetType || !next.surface) {
            return;
        }
        if (next.targetType === context.targetType
                && next.targetKey === context.targetKey
                && next.surface === context.surface
                && next.label === context.label) {
            return;
        }
        Object.assign(context, next);
        if (!layer || !window.EnderVault) {
            return;
        }
        clearRenderedNotes();
        try {
            await loadCurrentContext();
        } catch (error) {
            showToast("error", error.message || "Sticky notes could not be loaded.");
        }
    };

    window.EnderVaultStickyNotes = { setContext };
    document.addEventListener("endervault:sticky-context-changed", (event) => {
        void setContext(event.detail);
    });
    document.addEventListener("endervault:sticky-note-deleted", (event) => {
        const id = String(event.detail?.id || "");
        const state = states.get(id);
        if (!state) {
            return;
        }
        clearLocalBackup(state);
        state.endInteraction?.(false);
        window.clearTimeout(state.debounceTimer);
        window.clearTimeout(state.maxTimer);
        layoutObserver?.unobserve(state.card);
        state.card.remove();
        states.delete(id);
        updatePageExtent();
    });

    const detachShell = (host = layer) => {
        if (!layer || host !== layer) {
            return;
        }
        contextRevision += 1;
        clearRenderedNotes();
        window.cancelAnimationFrame(layoutFrame);
        layoutFrame = null;
        layoutObserver?.disconnect();
        layoutObserver = null;
        controlListeners.forEach((remove) => remove());
        controlListeners = [];
        layer.classList.remove("is-interacting");
        activePointerInteractions = 0;
        layer = null;
        appMain = null;
        controls = null;
    };

    const initialize = async () => {
        const nextControls = document.querySelector("[data-sticky-note-controls]");
        const nextMain = document.querySelector(".app-main");
        const nextLayer = document.querySelector("[data-sticky-note-layer]");
        if (!window.EnderVault || !nextControls || !nextMain || !nextLayer) {
            return;
        }
        if (layer === nextLayer && appMain === nextMain && controls === nextControls) {
            return;
        }
        detachShell();
        controls = nextControls;
        appMain = nextMain;
        layer = nextLayer;
        controls.hidden = false;
        if (window.ResizeObserver) {
            layoutObserver = new window.ResizeObserver(scheduleLayout);
            layoutObserver.observe(appMain);
        }

        const bindControl = (selector, handler) => {
            const button = controls.querySelector(selector);
            if (!button) {
                return;
            }
            button.addEventListener("click", handler);
            controlListeners.push(() => button.removeEventListener("click", handler));
        };
        bindControl("[data-sticky-note-add]", () => void createNote());
        bindControl("[data-sticky-note-visibility]", () => setAllHidden(!layer.hidden));
        bindControl("[data-sticky-note-trigger]", () => setAllHidden(!layer.hidden));
        window.EnderVaultContextMenus?.registerGlobalAction({
            id: "new-sticky-note",
            group: "sticky-note",
            label: "New sticky note",
            icon: "fas fa-note-sticky",
            visible: () => Boolean(layer),
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
            await loadCurrentContext();
        } catch (error) {
            showToast("error", error.message || "Sticky notes could not be loaded.");
        }
    };

    const flushBeforeLeaving = () => states.forEach((state) => {
        state.endInteraction?.();
        keepaliveFlush(state);
    });
    window.addEventListener("resize", scheduleLayout);
    window.addEventListener("pagehide", flushBeforeLeaving);
    document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "hidden") {
            flushBeforeLeaving();
        }
    });
    document.addEventListener("endervault:spa-shell-disposed", (event) => {
        if (event.detail?.host) {
            detachShell(event.detail.host);
        }
    });

    const initializeWhenReady = () => void initialize();
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", initializeWhenReady, { once: true });
    } else {
        initializeWhenReady();
    }
    document.addEventListener("endervault:spa-shell-ready", initializeWhenReady);
})();
