document.addEventListener("DOMContentLoaded", () => {
    const resetForm = document.getElementById("readonlySortResetForm");
    const interactiveSelector = "a[href], button, input, select, textarea, summary, label";
    const hitTargets = Array.from(document.querySelectorAll(".readonly-table tbody tr, .readonly-file-card"));

    document.body.classList.add("readonly-hit-targets");

    resetForm?.addEventListener("submit", async (event) => {
        event.preventDefault();
        const submitter = event.submitter;
        if (submitter) submitter.disabled = true;
        try {
            const body = await window.EnderVault.submitJsonForm(resetForm);
            const navigated = window.EnderVault.navigateWithNotification(body);
            if (!navigated) {
                window.EnderVault.showNotification(body.notification);
                if (submitter) submitter.disabled = false;
            }
        } catch (error) {
            window.EnderVault.showToast("error", error.message || "View options could not be reset.");
            if (submitter) submitter.disabled = false;
        }
    });

    const primaryLinkFor = (target) => target.querySelector("a[href]");

    const navigate = (target) => {
        const primaryLink = primaryLinkFor(target);
        if (primaryLink) {
            window.EnderVault?.navigate?.(primaryLink.href) || window.location.assign(primaryLink.href);
        }
    };

    hitTargets.forEach((target) => {
        const primaryLink = primaryLinkFor(target);
        if (!primaryLink) {
            return;
        }

        target.addEventListener("click", (event) => {
            if (event.target.closest(interactiveSelector)) {
                return;
            }
            navigate(target);
        });
    });
});
