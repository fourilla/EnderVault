const initializeFileTools = (root = document) => {
    const textEditors = root.matches?.("form[data-text-editor]")
        ? [root]
        : root.querySelectorAll("form[data-text-editor]");
    textEditors.forEach((form) => {
        initializeTextEditor(form);
    });
    const sharedPreviews = root.matches?.("[data-shared-text-preview]")
        ? [root]
        : root.querySelectorAll("[data-shared-text-preview]");
    sharedPreviews.forEach((preview) => {
        initializeSharedTextPreview(preview);
    });
};

const destroyFileTools = (root = document) => {
    const textEditors = root.matches?.("form[data-text-editor]")
        ? [root]
        : root.querySelectorAll("form[data-text-editor]");
    textEditors.forEach((form) => {
        form._endervaultFileToolsCleanup?.();
        delete form._endervaultFileToolsCleanup;
        delete form.dataset.textEditorBound;
    });
};

window.EnderVaultFileTools = {
    init: initializeFileTools,
    destroy: destroyFileTools
};

document.addEventListener("DOMContentLoaded", () => initializeFileTools());

const TEXT_EDITOR_FONT_SIZE_KEY = "endervault.textEditor.fontSize";
const TEXT_EDITOR_LINE_WRAP_KEY = "endervault.textEditor.lineWrap";
const TEXT_DRAFT_EDITOR_TOKEN_PREFIX = "endervault.textDraft.editorToken:";
const TEXT_DRAFT_DEBOUNCE_MS = 2000;
const TEXT_DRAFT_MAX_INTERVAL_MS = 15000;

const MODE_OPTIONS = [
    ["text/plain", "Plain"],
    ["markdown", "Markdown"],
    ["application/json", "JSON"],
    ["javascript", "JavaScript"],
    ["htmlmixed", "HTML"],
    ["css", "CSS"],
    ["xml", "XML"],
    ["text/x-java", "Java"],
    ["text/x-csrc", "C"],
    ["text/x-c++src", "C++"],
    ["text/x-csharp", "C#"],
    ["python", "Python"],
    ["ruby", "Ruby"],
    ["go", "Go"],
    ["rust", "Rust"],
    ["php", "PHP"],
    ["shell", "Shell"],
    ["powershell", "PowerShell"],
    ["sql", "SQL"],
    ["properties", "Properties"],
    ["yaml", "YAML"],
    ["toml", "TOML"],
    ["dockerfile", "Dockerfile"]
];

const EXTENSION_MODES = {
    md: "markdown",
    markdown: "markdown",
    json: "application/json",
    jsonl: "application/json",
    js: "javascript",
    mjs: "javascript",
    cjs: "javascript",
    ts: "javascript",
    tsx: "javascript",
    jsx: "javascript",
    html: "htmlmixed",
    htm: "htmlmixed",
    css: "css",
    xml: "xml",
    java: "text/x-java",
    c: "text/x-csrc",
    h: "text/x-csrc",
    cpp: "text/x-c++src",
    hpp: "text/x-c++src",
    cc: "text/x-c++src",
    cs: "text/x-csharp",
    py: "python",
    rb: "ruby",
    go: "go",
    rs: "rust",
    php: "php",
    sh: "shell",
    bash: "shell",
    zsh: "shell",
    bat: "shell",
    cmd: "shell",
    ps1: "powershell",
    sql: "sql",
    properties: "properties",
    conf: "properties",
    cfg: "properties",
    ini: "properties",
    yml: "yaml",
    yaml: "yaml",
    toml: "toml"
};

const FILENAME_MODES = {
    dockerfile: "dockerfile",
    makefile: "text/x-csrc",
    ".env": "properties",
    ".gitignore": "properties",
    ".gitattributes": "properties"
};

const initializeTextEditor = (form) => {
    if (!window.CodeMirror || form.dataset.textEditorBound === "true") {
        return;
    }
    form.dataset.textEditorBound = "true";
    let textarea = null;
    let codeMirror = null;
    let savedValue = "";
    let lastDraftedValue = "";
    let dirty = false;
    let draftResolved = false;
    let draftPaused = false;
    let debounceTimer = null;
    let maximumTimer = null;
    let draftRequest = null;
    let draftPending = false;
    let takeoverPreservesLocalContent = false;
    let draftId = null;
    let saveAsSuggestion = null;
    let draftAutosaveBlocked = false;
    let sourceMissingNotified = false;

    const path = form.querySelector("input[name='path']")?.value || "";
    const editorToken = textEditorToken(path);
    const editorTokenInput = form.querySelector("[data-text-editor-token]");
    const draftIdInput = form.querySelector("[data-text-draft-id]");
    const forceOverwriteInput = form.querySelector("[data-text-force-overwrite]");
    const draftState = form.querySelector("[data-text-draft-state]");
    const draftDialog = form.querySelector("[data-text-draft-dialog]");
    const draftMessage = form.querySelector("[data-text-draft-message]");
    const draftSourceWarning = form.querySelector("[data-text-draft-source-warning]");
    const markdownSourceTab = form.querySelector("[data-markdown-source-tab]");
    const markdownPreviewTab = form.querySelector("[data-markdown-preview-tab]");
    const markdownPreview = form.querySelector("[data-markdown-editor-preview]");
    const markdownPreviewBody = form.querySelector("[data-markdown-preview-body]");
    const markdownPreviewStatus = form.querySelector("[data-markdown-preview-status]");
    let lastRenderedMarkdown = null;

    if (editorTokenInput) {
        editorTokenInput.value = editorToken;
    }
    form.dataset.draftResolved = "false";

    const setDirty = (nextDirty) => {
        dirty = nextDirty;
        form.classList.toggle("is-dirty", dirty);
    };

    const currentValue = () => codeMirror ? codeMirror.getValue() : textarea?.value || "";

    const showMarkdownSource = () => {
        if (!markdownPreview) {
            return;
        }
        form.classList.remove("is-markdown-preview");
        markdownPreview.hidden = true;
        markdownSourceTab?.classList.add("is-active");
        markdownSourceTab?.setAttribute("aria-selected", "true");
        markdownPreviewTab?.classList.remove("is-active");
        markdownPreviewTab?.setAttribute("aria-selected", "false");
        window.setTimeout(() => codeMirror?.refresh(), 0);
    };

    const showMarkdownPreview = async () => {
        if (!markdownPreview || !markdownPreviewBody || !textarea) {
            return;
        }
        const source = currentValue();
        form.classList.add("is-markdown-preview");
        markdownPreview.hidden = false;
        markdownSourceTab?.classList.remove("is-active");
        markdownSourceTab?.setAttribute("aria-selected", "false");
        markdownPreviewTab?.classList.add("is-active");
        markdownPreviewTab?.setAttribute("aria-selected", "true");

        if (source === lastRenderedMarkdown) {
            return;
        }
        if (markdownPreviewStatus) {
            markdownPreviewStatus.hidden = false;
            markdownPreviewStatus.textContent = "Rendering Markdown...";
        }
        markdownPreviewBody.replaceChildren();
        markdownPreviewTab.disabled = true;

        try {
            if (!window.EnderVaultMarkdown) {
                throw new Error("Markdown renderer is unavailable.");
            }
            await new Promise((resolve) => window.requestAnimationFrame(resolve));
            await window.EnderVaultMarkdown.renderInto(markdownPreviewBody, source, path);
            if (markdownPreviewStatus) {
                markdownPreviewStatus.hidden = true;
            }
            lastRenderedMarkdown = source;
        } catch (error) {
            if (markdownPreviewStatus) {
                markdownPreviewStatus.hidden = false;
                markdownPreviewStatus.textContent = error.message || "Markdown preview could not be rendered.";
            }
            window.EnderVault.showToast(
                "error",
                error.message || "Markdown preview could not be rendered."
            );
        } finally {
            markdownPreviewTab.disabled = false;
        }
    };

    const setDraftState = (message, state = "") => {
        if (!draftState) {
            return;
        }
        draftState.textContent = message;
        draftState.title = message;
        draftState.classList.toggle("is-saved", state === "saved");
        draftState.classList.toggle("is-warning", state === "warning");
    };

    const rememberDraft = (status, suggestion = null) => {
        if (suggestion) {
            saveAsSuggestion = suggestion;
        }
        if (!status) {
            return;
        }
        if (status.exists && status.id) {
            draftId = status.id;
            draftAutosaveBlocked = false;
        } else if (!status.exists) {
            draftId = null;
        }
        if (draftIdInput) {
            draftIdInput.value = draftId || "";
        }
    };

    const syncTextarea = () => {
        if (codeMirror) {
            codeMirror.save();
        }
    };

    const clearDraftTimers = () => {
        window.clearTimeout(debounceTimer);
        window.clearTimeout(maximumTimer);
        debounceTimer = null;
        maximumTimer = null;
    };

    const lockEditorAfterSessionExpiry = () => {
        if (draftPaused) {
            return;
        }
        draftPaused = true;
        clearDraftTimers();
        const editButton = form.querySelector("[data-text-edit-toggle]");
        const saveButton = form.querySelector("[data-text-save-button]");
        if (codeMirror) {
            setEditorEditMode(form, codeMirror, editButton, saveButton, false, false);
        }
        if (editButton) {
            editButton.disabled = true;
        }
        if (textarea && !codeMirror) {
            textarea.readOnly = true;
        }
        setDraftState(
            "Session expired. Changes could not be saved. Log in again before continuing.",
            "warning"
        );
        window.EnderVault?.showToast(
            "error",
            "Your session expired, so the changes could not be saved. Log in again before continuing."
        );
    };

    const handleDraftError = (error, fallbackMessage) => {
        if (error.draftHandled) {
            return;
        }
        error.draftHandled = true;
        if (error.sessionExpired || error.status === 401 || error.status === 403) {
            lockEditorAfterSessionExpiry();
            return;
        }
        if (error.payload?.code === "LEASE_CONFLICT") {
            draftResolved = false;
            form.dataset.draftResolved = "false";
            openDraftDialog(
                error.payload.draft,
                dirty || form.classList.contains("is-editor-editing")
            );
            setDraftState("Draft editing is active elsewhere.", "warning");
            return;
        }
        if (error.payload?.code === "SOURCE_CHANGED") {
            setDraftState("The original changed. Your draft was retained.", "warning");
            window.EnderVault?.showToast("warning", error.message || fallbackMessage);
            return;
        }
        if (error.payload?.code === "SOURCE_MISSING") {
            rememberDraft(error.payload.draft, error.payload.saveAs);
            draftAutosaveBlocked = !draftId;
            setDraftState(
                draftId
                    ? "Original file missing. Changes are saved to the draft only."
                    : "Original file missing. Use Save to recover this text.",
                "warning"
            );
            if (!sourceMissingNotified) {
                sourceMissingNotified = true;
                window.EnderVault?.showToast(
                    "warning",
                    error.message || "The original file is missing. Save this text as a new file."
                );
            }
            return;
        }
        setDraftState("Draft autosave failed.", "warning");
        window.EnderVault?.showToast("error", error.message || fallbackMessage);
    };

    const draftFormData = (content = null, takeOver = false) => {
        const data = new FormData();
        const csrf = form.querySelector('input[name="_csrf"]');
        if (csrf) {
            data.append(csrf.name, csrf.value);
        }
        data.append("path", path);
        data.append("editorToken", editorToken);
        data.append("takeOver", takeOver ? "true" : "false");
        if (draftId) {
            data.append("draftId", draftId);
        }
        if (content !== null) {
            data.append("content", content);
        }
        return data;
    };

    const persistDraft = async () => {
        clearDraftTimers();
        if (draftPaused
                || draftAutosaveBlocked
                || !draftResolved
                || !form.classList.contains("is-editor-editing")) {
            return;
        }
        if (draftRequest) {
            draftPending = true;
            await draftRequest;
            if (draftPending) {
                draftPending = false;
                return persistDraft();
            }
            return;
        }

        const value = currentValue();
        if (value === lastDraftedValue) {
            return;
        }

        setDraftState("Saving draft...");
        draftRequest = window.EnderVault.requestJson(form.dataset.textDraftUrl, {
            method: "POST",
            body: draftFormData(value)
        });
        try {
            const body = await draftRequest;
            rememberDraft(body.draft, body.saveAs);
            lastDraftedValue = value;
            if (body.draft?.sourceMissing) {
                setDraftState("Original file missing. Changes are saved to the draft only.", "warning");
            } else {
                setDraftState(`Draft saved at ${new Date().toLocaleTimeString()}.`, "saved");
            }
        } catch (error) {
            handleDraftError(error, "Text draft could not be saved.");
            throw error;
        } finally {
            draftRequest = null;
        }

        if (draftPending) {
            draftPending = false;
            return persistDraft();
        }
    };

    const scheduleDraftSave = () => {
        if (draftPaused
                || draftAutosaveBlocked
                || !draftResolved
                || !form.classList.contains("is-editor-editing")
                || currentValue() === lastDraftedValue) {
            return;
        }
        window.clearTimeout(debounceTimer);
        debounceTimer = window.setTimeout(() => {
            persistDraft().catch(() => {
                // The editor state and toast already explain the failure.
            });
        }, TEXT_DRAFT_DEBOUNCE_MS);
        if (!maximumTimer) {
            maximumTimer = window.setTimeout(() => {
                persistDraft().catch(() => {
                    // The editor state and toast already explain the failure.
                });
            }, TEXT_DRAFT_MAX_INTERVAL_MS);
        }
    };

    const onContentChanged = () => {
        setDirty(currentValue() !== savedValue);
        scheduleDraftSave();
    };

    const bindEditor = (nextTextarea) => {
        if (!nextTextarea || nextTextarea.dataset.textEditorBound === "true") {
            return;
        }
        textarea = nextTextarea;
        textarea.dataset.textEditorBound = "true";
        if (markdownPreviewTab) {
            markdownPreviewTab.disabled = false;
        }
        savedValue = textarea.value;
        lastDraftedValue = textarea.value;
        setDirty(false);

        if (window.CodeMirror) {
            codeMirror = enhanceWithCodeMirror(form, textarea);
            codeMirror.on("change", onContentChanged);
            return;
        }

        textarea.addEventListener("input", onContentChanged);
    };

    const installTextContent = (content) => {
        if (!textarea) {
            const loadedTextarea = document.createElement("textarea");
            loadedTextarea.className = "text-editor-area";
            loadedTextarea.name = "content";
            loadedTextarea.spellcheck = false;
            loadedTextarea.value = content || "";
            const panel = form.querySelector("[data-text-load-panel]");
            if (panel) {
                panel.replaceWith(loadedTextarea);
            } else {
                form.append(loadedTextarea);
            }
            bindEditor(loadedTextarea);
            return;
        }
        if (codeMirror) {
            codeMirror.setValue(content || "");
        } else {
            textarea.value = content || "";
        }
    };

    const resolveDraft = (content, status = null) => {
        rememberDraft(status);
        installTextContent(content);
        lastDraftedValue = content || "";
        setDirty(currentValue() !== savedValue);
        draftResolved = true;
        form.dataset.draftResolved = "true";
        draftDialog?.close();
        setDraftState("Draft restored.", "saved");

        const editButton = form.querySelector("[data-text-edit-toggle]");
        const saveButton = form.querySelector("[data-text-save-button]");
        if (codeMirror) {
            setEditorEditMode(form, codeMirror, editButton, saveButton, true, true);
            codeMirror.focus();
        } else if (textarea) {
            textarea.readOnly = false;
            textarea.focus();
        }
    };

    const openDraftDialog = (status, preserveLocalContent = false) => {
        if (!draftDialog || !status?.exists) {
            return;
        }
        rememberDraft(status);
        takeoverPreservesLocalContent = preserveLocalContent;
        const ownedOrStale = status.owned || !status.active;
        const restoreButton = draftDialog.querySelector("[data-text-draft-restore]");
        const discardButton = draftDialog.querySelector("[data-text-draft-discard]");
        const takeoverButton = draftDialog.querySelector("[data-text-draft-takeover]");
        restoreButton.hidden = !ownedOrStale;
        discardButton.hidden = !ownedOrStale;
        takeoverButton.hidden = ownedOrStale;
        takeoverButton.textContent = preserveLocalContent
            ? "Take over with this text"
            : "Take over editing";
        draftSourceWarning.hidden = !status.sourceChanged && !status.sourceMissing;
        draftSourceWarning.textContent = status.sourceMissing
            ? "The original file is missing. This draft must be saved as a new file."
            : "The original file changed after this draft was created.";
        if (draftMessage) {
            draftMessage.textContent = ownedOrStale
                ? "A recoverable draft exists for this file."
                : preserveLocalContent
                    ? "Another editor owns the draft. Taking over will keep the text currently shown in this tab."
                    : "This draft is currently leased by another editor.";
        }
        if (!draftDialog.open) {
            draftDialog.showModal();
        }
    };

    const restoreDraft = async (takeOver) => {
        try {
            const body = await window.EnderVault.requestJson(form.dataset.textDraftRestoreUrl, {
                method: "POST",
                body: draftFormData(null, takeOver)
            });
            takeoverPreservesLocalContent = false;
            resolveDraft(body.content, body.draft);
        } catch (error) {
            handleDraftError(error, "Text draft could not be restored.");
        }
    };

    const takeOverDraft = async () => {
        if (!takeoverPreservesLocalContent) {
            await restoreDraft(true);
            return;
        }
        try {
            const body = await window.EnderVault.requestJson(form.dataset.textDraftUrl, {
                method: "POST",
                body: draftFormData(currentValue(), true)
            });
            rememberDraft(body.draft, body.saveAs);
            takeoverPreservesLocalContent = false;
            draftResolved = true;
            form.dataset.draftResolved = "true";
            lastDraftedValue = currentValue();
            draftDialog?.close();
            setDraftState(`Draft saved at ${new Date().toLocaleTimeString()}.`, "saved");
            if (body.notification) {
                window.EnderVault.showNotification(body.notification);
            }
        } catch (error) {
            handleDraftError(error, "Text draft could not be taken over.");
        }
    };

    const discardDraft = async () => {
        try {
            const body = await window.EnderVault.requestJson(form.dataset.textDraftDiscardUrl, {
                method: "POST",
                body: draftFormData()
            });
            rememberDraft(body.draft);
            draftResolved = true;
            form.dataset.draftResolved = "true";
            lastDraftedValue = currentValue();
            takeoverPreservesLocalContent = false;
            draftDialog?.close();
            setDraftState("No saved draft.");
            window.EnderVault.showNotification(body.notification);
        } catch (error) {
            handleDraftError(error, "Text draft could not be discarded.");
        }
    };

    const checkDraft = async () => {
        if (!window.EnderVault || !form.dataset.textDraftUrl) {
            draftResolved = true;
            form.dataset.draftResolved = "true";
            return;
        }
        const url = new URL(form.dataset.textDraftUrl, window.location.href);
        url.searchParams.set("path", path);
        url.searchParams.set("editorToken", editorToken);
        try {
            const body = await window.EnderVault.requestJson(url);
            rememberDraft(body.draft, body.saveAs);
            if (!body.draft?.exists) {
                draftResolved = true;
                form.dataset.draftResolved = "true";
                setDraftState("No saved draft.");
                return;
            }
            openDraftDialog(body.draft);
            setDraftState("A recoverable draft exists.", "warning");
        } catch (error) {
            handleDraftError(error, "Draft status could not be checked.");
        }
    };

    const offerSaveAs = async (suggestion = null) => {
        const proposed = suggestion || saveAsSuggestion || {};
        const directory = proposed.directoryPath || "";
        const filename = await window.EnderVault.askTextInput({
            title: "Save draft as new file",
            message: directory
                ? `The original file is missing. Recover this draft in /${directory}.`
                : "The original file is missing. Recover this draft in the vault root.",
            label: "File name",
            initialValue: proposed.filename || "recovered.txt",
            confirmLabel: "Save As"
        });
        if (filename == null) {
            setDraftState("Save As canceled. The draft was retained.", "warning");
            return;
        }

        const data = draftFormData(currentValue());
        data.set("name", filename);
        data.set("conflictPolicy", "ask");
        try {
            const body = await window.EnderVault.requestJsonResolvingConflicts(
                form.dataset.textDraftSaveAsUrl,
                {
                    method: "POST",
                    body: data
                }
            );
            if (body.redirectUrl) {
                rememberDraft({ exists: false });
                setDirty(false);
                window.EnderVault.navigateWithNotification(body);
                return;
            }
            setDraftState("Save As canceled. The draft was retained.", "warning");
            window.EnderVault.showNotification(body.notification);
        } catch (error) {
            handleDraftError(error, "Text draft could not be saved as a new file.");
        }
    };

    const handleBeforeUnload = (event) => {
        if (!dirty) {
            return;
        }
        event.preventDefault();
        event.returnValue = "";
    };
    window.addEventListener("beforeunload", handleBeforeUnload);

    form.addEventListener("submit", async (event) => {
        if (!window.EnderVault) {
            syncTextarea();
            return;
        }

        event.preventDefault();
        syncTextarea();

        const button = form.querySelector("[data-text-save-button]");
        if (codeMirror?.getOption("readOnly")) {
            window.EnderVault.showToast("info", "Enable edit mode before saving.");
            return;
        }

        if (button) {
            button.disabled = true;
        }

        try {
            await persistDraft();
            if (forceOverwriteInput) {
                forceOverwriteInput.value = "false";
            }
            let body;
            try {
                body = await window.EnderVault.submitJsonForm(form);
            } catch (error) {
                if (error.payload?.code === "SOURCE_MISSING") {
                    handleDraftError(error, "The original file is missing.");
                    await offerSaveAs(error.payload.saveAs);
                    return;
                }
                if (error.payload?.code !== "SOURCE_CHANGED"
                        || !window.confirm("The original file changed after this draft was created. Overwrite it with this draft?")) {
                    throw error;
                }
                if (forceOverwriteInput) {
                    forceOverwriteInput.value = "true";
                }
                body = await window.EnderVault.submitJsonForm(form);
            }
            savedValue = currentValue();
            lastDraftedValue = savedValue;
            rememberDraft(body.draft);
            setDirty(false);
            setDraftState("No saved draft.");
            window.EnderVault.showNotification(body.notification);
        } catch (error) {
            if (error.payload?.code === "SOURCE_MISSING") {
                handleDraftError(error, "The original file is missing.");
                await offerSaveAs(error.payload.saveAs);
            } else {
                handleDraftError(error, "Text save failed.");
            }
        } finally {
            if (forceOverwriteInput) {
                forceOverwriteInput.value = "false";
            }
            if (button) {
                button.disabled = !form.classList.contains("is-editor-editing") || draftPaused;
            }
        }
    });

    const loadText = async () => {
        if (!window.EnderVault || !form.dataset.textLoadUrl) {
            return;
        }
        const warning = form.querySelector("[data-text-load-panel] p")?.textContent
            || "Large files can slow down the page.";
        if (!window.confirm(`${warning}\n\nLoad this text file into the browser editor?`)) {
            return;
        }

        const button = form.querySelector("[data-text-load-button]");
        if (button) {
            button.disabled = true;
        }

        try {
            const body = await window.EnderVault.requestJson(form.dataset.textLoadUrl);
            installTextContent(body.text.content || "");

            const saveButton = form.querySelector("[data-text-save-button]");
            if (saveButton) {
                saveButton.disabled = body.text.editable === false;
            }

            if (codeMirror) {
                codeMirror.focus();
            } else if (textarea) {
                textarea.focus();
            }
        } catch (error) {
            window.EnderVault.showToast("error", error.message || "Text load failed.");
        } finally {
            if (button) {
                button.disabled = false;
            }
        }
    };

    bindEditor(form.querySelector("textarea[name='content']"));

    if (markdownPreviewTab && !textarea) {
        markdownPreviewTab.disabled = true;
    }
    markdownSourceTab?.addEventListener("click", showMarkdownSource);
    markdownPreviewTab?.addEventListener("click", showMarkdownPreview);
    form.addEventListener("text-editor-source-requested", showMarkdownSource);

    const loadButton = form.querySelector("[data-text-load-button]");
    if (loadButton) {
        loadButton.addEventListener("click", loadText);
    }

    form.addEventListener("text-draft-resolution-required", () => {
        if (draftDialog && !draftDialog.open) {
            draftDialog.showModal();
        }
    });
    draftDialog?.querySelector("[data-text-draft-restore]")?.addEventListener("click", () => restoreDraft(false));
    draftDialog?.querySelector("[data-text-draft-takeover]")?.addEventListener("click", takeOverDraft);
    draftDialog?.querySelector("[data-text-draft-discard]")?.addEventListener("click", discardDraft);
    draftDialog?.querySelector("[data-text-draft-view-original]")?.addEventListener("click", () => draftDialog.close());
    form._endervaultFileToolsCleanup = () => {
        clearDraftTimers();
        window.removeEventListener("beforeunload", handleBeforeUnload);
        form._endervaultEditorControlsCleanup?.();
        delete form._endervaultEditorControlsCleanup;
        if (codeMirror) {
            codeMirror.toTextArea();
            codeMirror = null;
        }
        document.body.classList.remove("is-text-editor-fullscreen");
    };
    checkDraft();
};

const textEditorToken = (path) => {
    const key = `${TEXT_DRAFT_EDITOR_TOKEN_PREFIX}${path}`;
    try {
        const existing = window.sessionStorage.getItem(key);
        if (existing) {
            return existing;
        }
        const token = window.crypto?.randomUUID?.()
            || `${Date.now().toString(36)}_${Math.random().toString(36).slice(2)}_${Math.random().toString(36).slice(2)}`;
        window.sessionStorage.setItem(key, token);
        return token;
    } catch (error) {
        return window.crypto?.randomUUID?.()
            || `${Date.now().toString(36)}_${Math.random().toString(36).slice(2)}_${Math.random().toString(36).slice(2)}`;
    }
};

const enhanceWithCodeMirror = (form, textarea) => {
    const fontSize = preferredFontSize();
    const lineWrapping = preferredLineWrapping();
    const mode = modeFor(form.dataset.textExtension, form.dataset.textName);
    const editor = window.CodeMirror.fromTextArea(textarea, {
        mode,
        theme: "material-darker",
        lineNumbers: true,
        lineWrapping,
        styleActiveLine: true,
        matchBrackets: true,
        autoCloseBrackets: true,
        indentUnit: 4,
        tabSize: 4,
        indentWithTabs: false,
        viewportMargin: 80,
        extraKeys: {
            "Ctrl-S": (cm) => {
                cm.save();
                form.requestSubmit();
            },
            "Cmd-S": (cm) => {
                cm.save();
                form.requestSubmit();
            }
        }
    });

    form.classList.add("is-codemirror-enhanced");
    applyFontSize(editor, fontSize);
    attachEditorControls(form, editor, mode, lineWrapping, fontSize);
    window.setTimeout(() => editor.refresh(), 0);
    return editor;
};

const initializeSharedTextPreview = (preview) => {
    if (!window.CodeMirror || preview.dataset.sharedTextPreviewBound === "true") {
        return;
    }

    const disclosure = preview.closest("details[data-shared-preview]");
    if (disclosure && !disclosure.open) {
        if (preview.dataset.sharedTextPreviewPending === "true") {
            return;
        }
        preview.dataset.sharedTextPreviewPending = "true";
        const initializeWhenOpened = () => {
            if (!disclosure.open) {
                return;
            }
            disclosure.removeEventListener("toggle", initializeWhenOpened);
            delete preview.dataset.sharedTextPreviewPending;
            initializeSharedTextPreview(preview);
        };
        disclosure.addEventListener("toggle", initializeWhenOpened);
        return;
    }

    const textarea = preview.querySelector("textarea[data-shared-text-source]");
    if (!textarea) {
        return;
    }

    preview.dataset.sharedTextPreviewBound = "true";

    try {
        const editor = window.CodeMirror.fromTextArea(textarea, {
            mode: modeFor(preview.dataset.textExtension, preview.dataset.textName),
            theme: "material-darker",
            lineNumbers: true,
            lineWrapping: preferredLineWrapping(),
            readOnly: true,
            styleActiveLine: false,
            matchBrackets: true,
            tabSize: 4,
            indentUnit: 4,
            viewportMargin: 80
        });

        preview.classList.add("is-codemirror-enhanced");
        applyFontSize(editor, preferredFontSize());
        window.setTimeout(() => editor.refresh(), 0);
    } catch (error) {
        preview.dataset.sharedTextPreviewBound = "false";
        console.warn("Shared text CodeMirror preview failed.", error);
    }
};

const attachEditorControls = (form, editor, mode, lineWrapping, fontSize) => {
    const toolbar = form.querySelector(".text-editor-toolbar");
    const saveButton = form.querySelector("[data-text-save-button]");
    if (!toolbar || toolbar.querySelector("[data-editor-controls]")) {
        return;
    }

    const controls = document.createElement("div");
    controls.className = "text-editor-controls js-only";
    controls.dataset.editorControls = "true";
    const canEdit = saveButton ? !saveButton.disabled : true;
    if (saveButton) {
        saveButton.classList.add("action-icon");
    }

    const modeSelect = document.createElement("select");
    modeSelect.className = "editor-control source-only-control";
    modeSelect.title = "Syntax mode";
    modeSelect.setAttribute("aria-label", "Syntax mode");
    MODE_OPTIONS.forEach(([value, label]) => {
        const option = document.createElement("option");
        option.value = value;
        option.textContent = label;
        option.selected = value === mode;
        modeSelect.append(option);
    });
    modeSelect.addEventListener("change", () => {
        editor.setOption("mode", modeSelect.value);
    });

    const fontSelect = document.createElement("select");
    fontSelect.className = "editor-control compact source-only-control";
    fontSelect.title = "Font size";
    fontSelect.setAttribute("aria-label", "Font size");
    [12, 14, 16, 18, 20, 24].forEach((size) => {
        const option = document.createElement("option");
        option.value = String(size);
        option.textContent = `${size}px`;
        option.selected = size === fontSize;
        fontSelect.append(option);
    });
    fontSelect.addEventListener("change", () => {
        const nextSize = Number.parseInt(fontSelect.value, 10);
        if (!Number.isFinite(nextSize)) {
            return;
        }
        window.localStorage.setItem(TEXT_EDITOR_FONT_SIZE_KEY, String(nextSize));
        applyFontSize(editor, nextSize);
    });

    const wrapButton = document.createElement("button");
    wrapButton.className = "ghost icon-button action-icon editor-toggle source-only-control";
    wrapButton.type = "button";
    wrapButton.title = "Toggle line wrap";
    wrapButton.setAttribute("aria-label", "Toggle line wrap");
    wrapButton.innerHTML = '<i class="fas fa-align-left" aria-hidden="true"></i>';
    setWrapButtonState(wrapButton, lineWrapping);
    wrapButton.addEventListener("click", () => {
        const enabled = !editor.getOption("lineWrapping");
        editor.setOption("lineWrapping", enabled);
        window.localStorage.setItem(TEXT_EDITOR_LINE_WRAP_KEY, enabled ? "true" : "false");
        setWrapButtonState(wrapButton, enabled);
        editor.refresh();
    });

    const searchButton = document.createElement("button");
    searchButton.className = "ghost icon-button action-icon source-only-control";
    searchButton.type = "button";
    searchButton.title = "Find in text";
    searchButton.setAttribute("aria-label", "Find in text");
    searchButton.innerHTML = '<i class="fas fa-magnifying-glass" aria-hidden="true"></i>';
    searchButton.addEventListener("click", () => {
        if (window.CodeMirror.commands.find) {
            window.CodeMirror.commands.find(editor);
        }
    });

    const fullscreenButton = document.createElement("button");
    fullscreenButton.className = "ghost icon-button action-icon editor-toggle";
    fullscreenButton.type = "button";
    fullscreenButton.title = "Toggle fullscreen editor";
    fullscreenButton.setAttribute("aria-label", "Toggle fullscreen editor");
    fullscreenButton.innerHTML = '<i class="fas fa-expand" aria-hidden="true"></i>';
    fullscreenButton.addEventListener("click", () => {
        setEditorFullscreen(form, editor, fullscreenButton, !form.classList.contains("is-editor-fullscreen"));
    });

    const handleEditorKeydown = (event) => {
        if (event.key !== "Escape" || !form.classList.contains("is-editor-fullscreen")) {
            return;
        }
        setEditorFullscreen(form, editor, fullscreenButton, false);
    };
    document.addEventListener("keydown", handleEditorKeydown);
    form._endervaultEditorControlsCleanup = () => {
        document.removeEventListener("keydown", handleEditorKeydown);
    };

    const editButton = document.createElement("button");
    editButton.className = "ghost icon-button action-icon editor-toggle source-only-control";
    editButton.type = "button";
    editButton.dataset.textEditToggle = "true";
    editButton.disabled = !canEdit;
    editButton.addEventListener("click", () => {
        const enabled = !form.classList.contains("is-editor-editing");
        if (enabled) {
            form.dispatchEvent(new CustomEvent("text-editor-source-requested"));
        }
        if (enabled && form.dataset.draftResolved === "false") {
            form.dispatchEvent(new CustomEvent("text-draft-resolution-required"));
            return;
        }
        setEditorEditMode(form, editor, editButton, saveButton, enabled, canEdit);
        if (enabled) {
            editor.focus();
        }
    });

    controls.append(modeSelect, fontSelect, searchButton, wrapButton, fullscreenButton, editButton);
    if (saveButton) {
        controls.append(saveButton);
    }
    toolbar.append(controls);
    setEditorEditMode(form, editor, editButton, saveButton, false, canEdit);
};

const modeFor = (extension, name) => {
    const normalizedName = (name || "").toLowerCase();
    const normalizedExtension = (extension || "").toLowerCase();
    return FILENAME_MODES[normalizedName] || EXTENSION_MODES[normalizedExtension] || "text/plain";
};

const preferredFontSize = () => {
    const stored = Number.parseInt(window.localStorage.getItem(TEXT_EDITOR_FONT_SIZE_KEY), 10);
    return [12, 14, 16, 18, 20, 24].includes(stored) ? stored : 14;
};

const preferredLineWrapping = () => window.localStorage.getItem(TEXT_EDITOR_LINE_WRAP_KEY) !== "false";

const applyFontSize = (editor, fontSize) => {
    editor.getWrapperElement().style.fontSize = `${fontSize}px`;
    editor.refresh();
};

const setWrapButtonState = (button, enabled) => {
    button.classList.toggle("is-active", enabled);
    button.setAttribute("aria-pressed", enabled ? "true" : "false");
};

const setEditorEditMode = (form, editor, editButton, saveButton, enabled, canEdit) => {
    const editing = Boolean(enabled && canEdit);
    form.classList.toggle("is-editor-editing", editing);
    editor.setOption("readOnly", editing ? false : true);

    if (editButton) {
        editButton.classList.toggle("is-active", editing);
        editButton.setAttribute("aria-pressed", editing ? "true" : "false");
        editButton.title = editing ? "Switch to read-only" : "Enable edit mode";
        editButton.setAttribute("aria-label", editButton.title);
        editButton.innerHTML = editing
            ? '<i class="fas fa-lock-open" aria-hidden="true"></i>'
            : '<i class="fas fa-pen-to-square" aria-hidden="true"></i>';
    }

    if (saveButton) {
        saveButton.disabled = !editing;
    }
};

const setEditorFullscreen = (form, editor, button, enabled) => {
    form.classList.toggle("is-editor-fullscreen", enabled);
    document.body.classList.toggle("is-text-editor-fullscreen", enabled);
    button.classList.toggle("is-active", enabled);
    button.setAttribute("aria-pressed", enabled ? "true" : "false");
    button.title = enabled ? "Exit fullscreen editor" : "Toggle fullscreen editor";
    button.setAttribute("aria-label", button.title);
    button.innerHTML = enabled
        ? '<i class="fas fa-compress" aria-hidden="true"></i>'
        : '<i class="fas fa-expand" aria-hidden="true"></i>';
    window.setTimeout(() => editor.refresh(), 0);
};
