(function () {
    const issueSelector = '.metadata-table-wrap input[name="issues"]';
    const selectAllSelector = ".metadata-table-wrap input[data-select-all]";
    const pickLabelSelector = ".metadata-table-wrap [data-select-pick-label]";
    const repairMessagesSelector = ".metadata-repair-messages";

    const issueCheckboxes = (scope = document) =>
        Array.from(scope.querySelectorAll(issueSelector));

    const repairableRows = () =>
        Array.from(document.querySelectorAll(".metadata-table-wrap tbody tr[data-metadata-repairable='true']"));

    const rowForCheckbox = (checkbox) => checkbox.closest("tr");

    const checkboxForRow = (row) => row?.querySelector(issueSelector) || null;

    const scopeForSelectAll = (checkbox) => checkbox.closest("table") || document;

    const selectionRequiredButtons = () =>
        Array.from(document.querySelectorAll("[data-metadata-selection-required]"));

    const setRowState = () => {
        issueCheckboxes().forEach((checkbox) => {
            const row = rowForCheckbox(checkbox);
            if (!row) {
                return;
            }
            row.classList.toggle("is-selected", checkbox.checked);
            row.setAttribute("aria-selected", checkbox.checked ? "true" : "false");
        });
    };

    const syncSelectAll = () => {
        document.querySelectorAll(selectAllSelector).forEach((checkbox) => {
            const checkboxes = issueCheckboxes(scopeForSelectAll(checkbox));
            const selectedCount = checkboxes.filter((candidate) => candidate.checked).length;
            checkbox.disabled = checkboxes.length === 0;
            checkbox.checked = checkboxes.length > 0 && selectedCount === checkboxes.length;
            checkbox.indeterminate = selectedCount > 0 && selectedCount < checkboxes.length;
        });
    };

    const syncSelectionButtons = () => {
        const selectedCount = issueCheckboxes().filter((checkbox) => checkbox.checked).length;
        selectionRequiredButtons().forEach((button) => {
            button.disabled = selectedCount === 0;
            button.title = selectedCount === 0
                    ? "Select metadata issues first"
                    : (button.dataset.readyTitle || "Repair selected issues");
        });
    };

    const syncSelection = () => {
        document.querySelectorAll(pickLabelSelector).forEach((label) => {
            label.hidden = true;
        });
        setRowState();
        syncSelectAll();
        syncSelectionButtons();
    };

    const clearSelection = () => {
        issueCheckboxes().forEach((checkbox) => {
            checkbox.checked = false;
        });
        syncSelection();
    };

    const toggleRow = (row) => {
        const checkbox = checkboxForRow(row);
        if (!checkbox) {
            return;
        }
        checkbox.checked = !checkbox.checked;
        syncSelection();
    };

    const isControlTarget = (target) =>
        Boolean(target.closest("button, input, label, select, textarea, a"));

    const removeRowsForTokens = (tokens) => {
        const tokenSet = new Set(tokens || []);
        if (tokenSet.size === 0) {
            return;
        }
        document.querySelectorAll(".metadata-table-wrap tbody tr[data-metadata-token]").forEach((row) => {
            if (tokenSet.has(row.dataset.metadataToken)) {
                row.remove();
            }
        });
    };

    const ensureCleanMessage = (article) => {
        let message = article.querySelector(".metadata-clean-message");
        if (message) {
            return message;
        }
        message = document.createElement("p");
        message.className = "dashboard-empty metadata-clean-message";
        message.textContent = "No consistency issues found in this area.";
        const header = article.querySelector(".settings-subsection-heading");
        header?.insertAdjacentElement("afterend", message);
        return message;
    };

    const updateAreaStatuses = () => {
        document.querySelectorAll(".metadata-area-result").forEach((article) => {
            const rows = Array.from(article.querySelectorAll(".metadata-table-wrap tbody tr[data-metadata-token]"));
            const status = article.querySelector("[data-metadata-area-status]");
            if (rows.length === 0) {
                article.querySelector(".metadata-table-wrap")?.remove();
                ensureCleanMessage(article);
                if (status) {
                    status.className = "status-badge active";
                    status.textContent = "Clean";
                }
                return;
            }

            article.querySelector(".metadata-clean-message")?.remove();
            if (status) {
                status.className = "status-badge warning";
                status.textContent = `${rows.length} issues`;
            }
        });
    };

    const updateSummary = (body = {}) => {
        const issueCount = Number.isInteger(body.issueCount)
                ? body.issueCount
                : document.querySelectorAll(".metadata-table-wrap tbody tr[data-metadata-token]").length;
        const repairableCount = Number.isInteger(body.repairableCount)
                ? body.repairableCount
                : repairableRows().length;

        const issueCountElement = document.querySelector("[data-metadata-issue-count]");
        const repairableCountElement = document.querySelector("[data-metadata-repairable-count]");
        if (issueCountElement) {
            issueCountElement.textContent = String(issueCount);
        }
        if (repairableCountElement) {
            repairableCountElement.textContent = String(repairableCount);
        }

        const reportStatus = document.querySelector("[data-metadata-report-status]");
        if (reportStatus) {
            if (issueCount === 0) {
                reportStatus.className = "status-badge active";
                reportStatus.textContent = "Healthy";
            } else {
                reportStatus.className = "status-badge warning";
                reportStatus.textContent = `${issueCount} issues`;
            }
        }

        document.querySelectorAll("[data-metadata-actions]").forEach((actions) => {
            actions.hidden = repairableCount === 0;
        });
    };

    const renderRepairMessages = (messages = []) => {
        let container = document.querySelector(repairMessagesSelector);
        if (!messages.length) {
            container?.remove();
            return;
        }
        if (!container) {
            container = document.createElement("div");
            container.className = "metadata-repair-messages";
            const actions = document.querySelector("[data-metadata-actions]");
            actions?.insertAdjacentElement("beforebegin", container);
        }

        container.replaceChildren();
        const title = document.createElement("h3");
        title.textContent = "Repair Messages";
        const list = document.createElement("ul");
        messages.forEach((message) => {
            const item = document.createElement("li");
            item.textContent = message;
            list.append(item);
        });
        container.append(title, list);
    };

    const handleRepair = (body = {}) => {
        removeRowsForTokens(body.repairedTokens || []);
        renderRepairMessages(body.messages || []);
        updateAreaStatuses();
        updateSummary(body);
        clearSelection();
    };

    document.addEventListener("DOMContentLoaded", () => {
        document.body.classList.add("metadata-selection-enhanced");
        selectionRequiredButtons().forEach((button) => {
            button.dataset.readyTitle = button.title || "Repair selected issues";
        });

        document.addEventListener("click", (event) => {
            const row = event.target.closest(".metadata-table-wrap tbody tr");
            if (!row || !checkboxForRow(row) || isControlTarget(event.target)) {
                return;
            }
            event.preventDefault();
            toggleRow(row);
        });

        document.addEventListener("change", (event) => {
            if (event.target.matches(selectAllSelector)) {
                issueCheckboxes(scopeForSelectAll(event.target)).forEach((checkbox) => {
                    checkbox.checked = event.target.checked;
                });
                syncSelection();
                return;
            }

            if (issueCheckboxes().includes(event.target)) {
                syncSelection();
            }
        });

        syncSelection();
    });

    window.EnderVaultMetadata = {
        handleRepair,
        syncSelection
    };
})();
