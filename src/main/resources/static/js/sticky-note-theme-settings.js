(() => {
    const initialize = () => {
        const editor = document.querySelector("[data-sticky-note-theme-editor]");
        if (!editor) {
            return;
        }

        const preview = editor.querySelector("[data-sticky-note-theme-preview]");
        const resetButton = editor.querySelector("[data-sticky-note-theme-reset]");
        const contrastStatus = editor.querySelector("[data-sticky-note-theme-contrast]");
        const inputs = {
            background: editor.querySelector('[data-sticky-note-color="background"]'),
            border: editor.querySelector('[data-sticky-note-color="border"]'),
            text: editor.querySelector('[data-sticky-note-color="text"]')
        };
        const outputs = {
            background: editor.querySelector('[data-sticky-note-color-value="background"]'),
            border: editor.querySelector('[data-sticky-note-color-value="border"]'),
            text: editor.querySelector('[data-sticky-note-color-value="text"]')
        };

        if (!preview || Object.values(inputs).some((input) => !input)) {
            return;
        }

        const normalizeColor = (value) => String(value || "").toUpperCase();

        const relativeLuminance = (hex) => {
            const channels = [1, 3, 5].map((offset) => Number.parseInt(hex.slice(offset, offset + 2), 16) / 255);
            const linear = channels.map((channel) =>
                channel <= 0.04045
                    ? channel / 12.92
                    : ((channel + 0.055) / 1.055) ** 2.4);
            return (0.2126 * linear[0]) + (0.7152 * linear[1]) + (0.0722 * linear[2]);
        };

        const contrastRatio = (first, second) => {
            const brighter = Math.max(relativeLuminance(first), relativeLuminance(second));
            const darker = Math.min(relativeLuminance(first), relativeLuminance(second));
            return (brighter + 0.05) / (darker + 0.05);
        };

        const syncPreview = () => {
            const colors = {
                background: normalizeColor(inputs.background.value),
                border: normalizeColor(inputs.border.value),
                text: normalizeColor(inputs.text.value)
            };
            preview.style.setProperty("--sticky-note-preview-bg", colors.background);
            preview.style.setProperty("--sticky-note-preview-border", colors.border);
            preview.style.setProperty("--sticky-note-preview-text", colors.text);

            Object.entries(outputs).forEach(([key, output]) => {
                if (output) {
                    output.textContent = colors[key];
                }
            });

            if (contrastStatus) {
                const ratio = contrastRatio(colors.background, colors.text);
                const lowContrast = ratio < 4.5;
                contrastStatus.textContent = lowContrast
                    ? `Text contrast ${ratio.toFixed(2)}:1. A ratio of 4.5:1 or higher is recommended.`
                    : `Text contrast ${ratio.toFixed(2)}:1.`;
                contrastStatus.classList.toggle("is-warning", lowContrast);
            }
        };

        Object.values(inputs).forEach((input) => input.addEventListener("input", syncPreview));

        resetButton?.addEventListener("click", () => {
            inputs.background.value = editor.dataset.defaultBackground;
            inputs.border.value = editor.dataset.defaultBorder;
            inputs.text.value = editor.dataset.defaultText;
            syncPreview();
        });

        document.addEventListener("endervault:general-settings-saved", (event) => {
            if (event.detail?.form !== editor.closest("form")) {
                return;
            }
            document.documentElement.style.setProperty("--sticky-note-bg", normalizeColor(inputs.background.value));
            document.documentElement.style.setProperty("--sticky-note-border", normalizeColor(inputs.border.value));
            document.documentElement.style.setProperty("--sticky-note-text", normalizeColor(inputs.text.value));
        });

        syncPreview();
    };

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", initialize, { once: true });
    } else {
        initialize();
    }
})();
