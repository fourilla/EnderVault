document.addEventListener("DOMContentLoaded", () => {
    const interactiveSelector = "a[href], button, input, select, textarea, summary, label";
    const hitTargets = Array.from(document.querySelectorAll(".readonly-table tbody tr, .readonly-file-card"));

    document.body.classList.add("readonly-hit-targets");

    const primaryLinkFor = (target) => target.querySelector("a[href]");

    const navigate = (target) => {
        const primaryLink = primaryLinkFor(target);
        if (primaryLink) {
            window.location.href = primaryLink.href;
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
