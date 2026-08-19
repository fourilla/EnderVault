(() => {
    const initialize = () => {
        const center = document.querySelector("[data-notification-center]");
        if (!center || !window.EnderVault) {
            return;
        }

        const trigger = center.querySelector("[data-notification-center-toggle]");
        const popover = center.querySelector(".topbar-control-popover");
        const count = center.querySelector("[data-notification-count]");
        const indicator = center.querySelector("[data-notification-indicator]");
        const list = center.querySelector("[data-notification-list]");
        const empty = center.querySelector("[data-notification-empty]");
        const reviewAll = center.querySelector("[data-notification-review-all]");
        const viewportMargin = 8;

        const position = () => {
            const triggerRect = trigger.getBoundingClientRect();
            const popoverWidth = popover.getBoundingClientRect().width;
            const centeredLeft = triggerRect.left + (triggerRect.width - popoverWidth) / 2;
            const maximumLeft = Math.max(viewportMargin, window.innerWidth - popoverWidth - viewportMargin);
            popover.style.top = `${Math.round(triggerRect.bottom)}px`;
            popover.style.left = `${Math.round(Math.max(viewportMargin, Math.min(centeredLeft, maximumLeft)))}px`;
            popover.classList.add("is-viewport-positioned");
        };

        const close = () => {
            center.classList.remove("is-open");
            trigger.setAttribute("aria-expanded", "false");
        };

        const itemElement = (item) => {
            const link = document.createElement("a");
            link.className = "notification-center-item";
            link.href = item.href;
            const icon = document.createElement("i");
            icon.className = "fas fa-file-circle-exclamation";
            icon.setAttribute("aria-hidden", "true");
            const content = document.createElement("span");
            const title = document.createElement("strong");
            title.textContent = item.title;
            const detail = document.createElement("small");
            detail.textContent = item.detail;
            const time = document.createElement("time");
            time.textContent = item.createdLabel;
            content.append(title, detail, time);
            link.append(icon, content);
            return link;
        };

        const refresh = async () => {
            try {
                const response = await window.EnderVault.requestJson("/api/v1/notifications");
                count.textContent = `${response.actionableCount} pending`;
                indicator.hidden = response.actionableCount === 0;
                trigger.classList.toggle("has-notifications", response.actionableCount > 0);
                trigger.title = response.actionableCount > 0
                    ? `${response.actionableCount} pending decision(s)`
                    : "Notifications";
                list.replaceChildren(...response.items.map(itemElement));
                list.hidden = response.items.length === 0;
                empty.hidden = response.items.length !== 0;
                reviewAll.href = response.reviewAllHref;
            } catch (error) {
                count.textContent = "Unavailable";
            }
        };

        trigger.addEventListener("click", () => {
            const opening = !center.classList.contains("is-open");
            center.classList.toggle("is-open", opening);
            trigger.setAttribute("aria-expanded", String(opening));
            if (opening) {
                position();
                void refresh();
            }
        });
        document.addEventListener("pointerdown", (event) => {
            if (!center.contains(event.target)) {
                close();
            }
        });
        document.addEventListener("keydown", (event) => {
            if (event.key === "Escape") {
                close();
            }
        });
        window.addEventListener("focus", () => void refresh());
        window.addEventListener("resize", () => center.classList.contains("is-open") && position());
        window.setInterval(() => void refresh(), 20000);

        window.EnderVaultNotificationCenter = { refresh, close };
        void refresh();
    };

    document.readyState === "loading"
        ? document.addEventListener("DOMContentLoaded", initialize, { once: true })
        : initialize();
})();
