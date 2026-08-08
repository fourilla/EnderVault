(() => {
    const initialize = () => {
        const controls = Array.from(document.querySelectorAll(".topbar-control"));
        const viewportMargin = 8;

        const positionPopover = (control) => {
            const trigger = control.querySelector(".topbar-control-trigger");
            const popover = control.querySelector(".topbar-control-popover");
            if (!trigger || !popover) {
                return;
            }

            const triggerRect = trigger.getBoundingClientRect();
            const popoverWidth = popover.getBoundingClientRect().width;
            const centeredLeft = triggerRect.left + (triggerRect.width - popoverWidth) / 2;
            const maximumLeft = Math.max(viewportMargin, window.innerWidth - popoverWidth - viewportMargin);
            const left = Math.max(viewportMargin, Math.min(centeredLeft, maximumLeft));

            popover.style.top = `${Math.round(triggerRect.bottom)}px`;
            popover.style.left = `${Math.round(left)}px`;
            popover.classList.add("is-viewport-positioned");
        };

        controls.forEach((control) => {
            positionPopover(control);
            control.addEventListener("pointerenter", () => positionPopover(control));
            control.addEventListener("focusin", () => positionPopover(control));
        });

        const repositionOpenPopovers = () => controls.forEach((control) => {
            if (control.matches(":hover") || control.contains(document.activeElement)) {
                positionPopover(control);
            }
        });

        window.addEventListener("resize", repositionOpenPopovers);
        document.addEventListener("scroll", repositionOpenPopovers, true);
    };

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", initialize, { once: true });
    } else {
        initialize();
    }
})();
