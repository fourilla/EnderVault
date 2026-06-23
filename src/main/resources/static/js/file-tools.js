document.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll("form[data-text-editor]").forEach((form) => {
        initializeTextEditor(form);
    });
    document.querySelectorAll("[data-shared-text-preview]").forEach((preview) => {
        initializeSharedTextPreview(preview);
    });
});

const TEXT_EDITOR_FONT_SIZE_KEY = "endervault.textEditor.fontSize";
const TEXT_EDITOR_LINE_WRAP_KEY = "endervault.textEditor.lineWrap";

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
    let textarea = null;
    let codeMirror = null;
    let savedValue = "";
    let dirty = false;

    const setDirty = (nextDirty) => {
        dirty = nextDirty;
        form.classList.toggle("is-dirty", dirty);
    };

    const currentValue = () => codeMirror ? codeMirror.getValue() : textarea?.value || "";

    const syncTextarea = () => {
        if (codeMirror) {
            codeMirror.save();
        }
    };

    const bindEditor = (nextTextarea) => {
        if (!nextTextarea || nextTextarea.dataset.textEditorBound === "true") {
            return;
        }
        textarea = nextTextarea;
        textarea.dataset.textEditorBound = "true";
        savedValue = textarea.value;
        setDirty(false);

        if (window.CodeMirror) {
            codeMirror = enhanceWithCodeMirror(form, textarea);
            codeMirror.on("change", () => {
                setDirty(currentValue() !== savedValue);
            });
            return;
        }

        textarea.addEventListener("input", () => {
            setDirty(currentValue() !== savedValue);
        });
    };

    window.addEventListener("beforeunload", (event) => {
        if (!dirty) {
            return;
        }
        event.preventDefault();
        event.returnValue = "";
    });

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
            const body = await window.EnderVault.submitJsonForm(form);
            savedValue = currentValue();
            setDirty(false);
            window.EnderVault.showNotification(body.notification);
        } catch (error) {
            window.EnderVault.showToast("error", error.message || "Text save failed.");
        } finally {
            if (button) {
                button.disabled = false;
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
            const loadedTextarea = document.createElement("textarea");
            loadedTextarea.className = "text-editor-area";
            loadedTextarea.name = "content";
            loadedTextarea.spellcheck = false;
            loadedTextarea.value = body.text.content || "";

            const panel = form.querySelector("[data-text-load-panel]");
            if (panel) {
                panel.replaceWith(loadedTextarea);
            } else {
                form.append(loadedTextarea);
            }

            const saveButton = form.querySelector("[data-text-save-button]");
            if (saveButton) {
                saveButton.disabled = body.text.editable === false;
            }

            const toolbarLabel = form.querySelector(".text-editor-toolbar span");
            if (toolbarLabel) {
                toolbarLabel.textContent = `Editable up to ${body.text.manualLoadSizeLabel}`;
            }

            bindEditor(loadedTextarea);
            if (codeMirror) {
                codeMirror.focus();
            } else {
                loadedTextarea.focus();
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

    const loadButton = form.querySelector("[data-text-load-button]");
    if (loadButton) {
        loadButton.addEventListener("click", loadText);
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
    modeSelect.className = "editor-control";
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
    fontSelect.className = "editor-control compact";
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
    wrapButton.className = "ghost icon-button action-icon editor-toggle";
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
    searchButton.className = "ghost icon-button action-icon";
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

    document.addEventListener("keydown", (event) => {
        if (event.key !== "Escape" || !form.classList.contains("is-editor-fullscreen")) {
            return;
        }
        setEditorFullscreen(form, editor, fullscreenButton, false);
    });

    const editButton = document.createElement("button");
    editButton.className = "ghost icon-button action-icon editor-toggle";
    editButton.type = "button";
    editButton.disabled = !canEdit;
    editButton.addEventListener("click", () => {
        const enabled = !form.classList.contains("is-editor-editing");
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
