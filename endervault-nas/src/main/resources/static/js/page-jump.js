(function () {
    const readyAttribute = "data-page-jump-ready";

    const parseInteger = (value) => {
        const normalized = String(value ?? "").trim();
        if (!/^-?\d+$/.test(normalized)) {
            return null;
        }
        const parsed = Number.parseInt(normalized, 10);
        return Number.isSafeInteger(parsed) ? parsed : null;
    };

    const pageWithinRange = (page, total) => Math.min(Math.max(page, 1), total);

    const navigateToPage = (trigger, page) => {
        const param = trigger.dataset.pageParam || "page";
        const url = new URL(window.location.href);
        url.searchParams.set(param, String(page));
        window.location.assign(url.toString());
    };

    const promptFallback = (trigger) => {
        const current = parseInteger(trigger.dataset.pageCurrent) || 1;
        const total = parseInteger(trigger.dataset.pageTotal) || current;
        const answer = window.prompt(`Go to page (1-${total})`, String(current));
        if (answer === null) {
            return;
        }
        const page = parseInteger(answer);
        if (page === null) {
            return;
        }
        navigateToPage(trigger, pageWithinRange(page, total));
    };

    const ensureDialog = () => {
        let dialog = document.getElementById("pageJumpDialog");
        if (dialog) {
            return dialog;
        }

        dialog = document.createElement("dialog");
        dialog.id = "pageJumpDialog";
        dialog.className = "page-jump-dialog";
        dialog.innerHTML = `
            <form method="dialog" class="page-jump-card" novalidate>
                <header class="page-jump-header">
                    <div>
                        <h2>Go to page</h2>
                        <p data-page-jump-help>Enter a page number.</p>
                    </div>
                    <button class="ghost icon-button action-icon" data-page-jump-close type="button" title="Close" aria-label="Close">
                        <i class="fas fa-xmark" aria-hidden="true"></i>
                    </button>
                </header>
                <label class="page-jump-field">
                    <span>Page</span>
                    <input data-page-jump-input type="number" min="1" step="1" required>
                </label>
                <div class="page-jump-actions">
                    <button class="ghost" data-page-jump-close type="button">Cancel</button>
                    <button class="primary" value="go" type="submit">Go</button>
                </div>
            </form>
        `;
        document.body.append(dialog);

        const form = dialog.querySelector("form");
        const input = dialog.querySelector("[data-page-jump-input]");
        const help = dialog.querySelector("[data-page-jump-help]");

        form.addEventListener("submit", (event) => {
            event.preventDefault();
            const context = dialog._pageJumpContext;
            if (!context) {
                dialog.close("cancel");
                return;
            }

            const page = parseInteger(input.value);
            if (page === null) {
                return;
            }

            dialog.close("go");
            navigateToPage(context.trigger, pageWithinRange(page, context.total));
        });

        dialog.querySelectorAll("[data-page-jump-close]").forEach((button) => {
            button.addEventListener("click", () => dialog.close("cancel"));
        });
        dialog.addEventListener("click", (event) => {
            if (event.target === dialog) {
                dialog.close("cancel");
            }
        });

        dialog._pageJumpElements = { input, help };
        return dialog;
    };

    const openDialog = (trigger) => {
        if (typeof HTMLDialogElement === "undefined") {
            promptFallback(trigger);
            return;
        }

        const current = parseInteger(trigger.dataset.pageCurrent) || 1;
        const total = parseInteger(trigger.dataset.pageTotal) || current;
        const dialog = ensureDialog();
        const { input, help } = dialog._pageJumpElements;

        dialog._pageJumpContext = { trigger, total };
        help.textContent = `Enter a page from 1 to ${total}.`;
        input.min = "1";
        input.value = String(current);

        dialog.showModal();
        input.focus();
        input.select();
    };

    const enhancePageJump = (root = document) => {
        root.querySelectorAll(`[data-page-jump]:not([${readyAttribute}])`).forEach((trigger) => {
            trigger.setAttribute(readyAttribute, "true");
            trigger.classList.add("is-page-jump-enabled");
            trigger.setAttribute("role", "button");
            trigger.setAttribute("tabindex", "0");
            trigger.setAttribute("title", "Go to page");
            trigger.setAttribute("aria-label", `${trigger.textContent.trim()}. Go to page.`);

            trigger.addEventListener("click", () => openDialog(trigger));
            trigger.addEventListener("keydown", (event) => {
                if (event.key !== "Enter" && event.key !== " ") {
                    return;
                }
                event.preventDefault();
                openDialog(trigger);
            });
        });
    };

    document.addEventListener("DOMContentLoaded", () => enhancePageJump());
    document.addEventListener("endervault:listing-refreshed", (event) => {
        enhancePageJump(event.target instanceof Document ? event.target : document);
    });
})();
