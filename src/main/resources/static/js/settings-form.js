document.addEventListener("DOMContentLoaded", () => {
    const forms = Array.from(document.querySelectorAll("form[data-settings-form]"));
    if (forms.length === 0) {
        return;
    }

    const controlState = (form) => Array.from(form.elements)
        .filter((control) => control.name && control.name !== "_csrf")
        .filter((control) => !["button", "submit", "reset"].includes(control.type))
        .map((control) => {
            if (control.type === "checkbox" || control.type === "radio") {
                return [control.name, control.type, control.value, control.checked];
            }
            if (control instanceof HTMLSelectElement && control.multiple) {
                return [control.name, control.type, Array.from(control.selectedOptions, (option) => option.value)];
            }
            return [control.name, control.type, control.value];
        });

    const snapshot = (form) => JSON.stringify(controlState(form));
    const dirtyForms = new Set();

    const syncForm = (form) => {
        const dirty = snapshot(form) !== form.dataset.settingsBaseline;
        const bar = form.querySelector("[data-settings-save-bar]");
        const state = bar?.querySelector("[data-settings-save-state]");
        const save = bar?.querySelector("[data-settings-save]");
        const discard = bar?.querySelector("[data-settings-discard]");

        form.dataset.settingsDirty = String(dirty);
        bar?.classList.toggle("is-dirty", dirty);
        if (state) {
            state.textContent = dirty ? "Unsaved changes" : "No unsaved changes";
        }
        if (save) {
            save.disabled = !dirty;
        }
        if (discard) {
            discard.disabled = !dirty;
        }
        if (dirty) {
            dirtyForms.add(form);
        } else {
            dirtyForms.delete(form);
        }
    };

    const dispatchControlEvents = (form) => {
        Array.from(form.elements).forEach((control) => {
            if (!(control instanceof HTMLElement) || !control.name || control.name === "_csrf") {
                return;
            }
            control.dispatchEvent(new Event("input", { bubbles: true }));
            control.dispatchEvent(new Event("change", { bubbles: true }));
        });
    };

    forms.forEach((form) => {
        form.dataset.settingsBaseline = snapshot(form);
        syncForm(form);

        form.addEventListener("input", () => syncForm(form));
        form.addEventListener("change", () => syncForm(form));
        form.addEventListener("endervault:ajax-success", () => {
            form.querySelectorAll("[data-clear-after-save]").forEach((control) => {
                control.value = "";
            });
            form.dataset.settingsBaseline = snapshot(form);
            syncForm(form);
        });

        form.querySelector("[data-settings-discard]")?.addEventListener("click", () => {
            form.reset();
            dispatchControlEvents(form);
            syncForm(form);
        });
    });

    window.addEventListener("beforeunload", (event) => {
        if (dirtyForms.size === 0) {
            return;
        }
        event.preventDefault();
        event.returnValue = "";
    });

    document.addEventListener("keydown", (event) => {
        if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== "s") {
            return;
        }
        const form = forms.find((candidate) => candidate.dataset.settingsDirty === "true");
        if (!form) {
            return;
        }
        event.preventDefault();
        form.requestSubmit(form.querySelector("[data-settings-save]"));
    });
});
