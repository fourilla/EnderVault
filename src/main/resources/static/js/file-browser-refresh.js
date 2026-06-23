document.addEventListener("DOMContentLoaded", () => {
    const syncToolbarState = (targetUrl) => {
        const values = {
            path: targetUrl.searchParams.get("path") || "",
            view: targetUrl.searchParams.get("view"),
            sort: targetUrl.searchParams.get("sort"),
            dir: targetUrl.searchParams.get("dir"),
            page: targetUrl.searchParams.get("page") || "1",
            size: targetUrl.searchParams.get("size"),
            q: targetUrl.searchParams.get("q") || ""
        };

        Object.entries(values).forEach(([name, value]) => {
            if (value === null) {
                return;
            }
            document.querySelectorAll(`input[type="hidden"][name="${name}"]`).forEach((input) => {
                input.value = value;
            });
        });
    };

    const refreshListing = async (url = window.location.href) => {
        const targetUrl = new URL(url, window.location.href);
        const response = await fetch(targetUrl, {
            headers: { "X-Requested-With": "fetch" },
            credentials: "same-origin"
        });
        if (!response.ok) {
            return;
        }

        const documentText = await response.text();
        const nextDocument = new DOMParser().parseFromString(documentText, "text/html");
        const currentMain = document.querySelector("main.workspace");
        const nextMain = nextDocument.querySelector("main.workspace");
        if (!currentMain || !nextMain) {
            return;
        }

        currentMain.querySelectorAll(".browser-section, .browser-grid-empty").forEach((element) => element.remove());
        const nextNodes = Array.from(nextMain.children)
                .filter((element) => element.matches(".browser-section, .browser-grid-empty"));
        nextNodes.forEach((node) => currentMain.append(document.importNode(node, true)));
        if (targetUrl.href !== window.location.href) {
            window.history.replaceState({}, "", targetUrl);
        }
        syncToolbarState(targetUrl);
        document.dispatchEvent(new CustomEvent("endervault:listing-refreshed", {
            detail: { url: targetUrl }
        }));
    };

    const refreshState = {
        timer: null,
        pendingUrl: null
    };

    const requestListingRefresh = (url = window.location.href) => {
        refreshState.pendingUrl = url;
        window.clearTimeout(refreshState.timer);
        refreshState.timer = window.setTimeout(async () => {
            try {
                await refreshListing(refreshState.pendingUrl || window.location.href);
            } catch (error) {
                window.EnderVault.showToast("error", "The file list could not be refreshed.");
            }
        }, 450);
    };

    window.EnderVaultFileBrowser = {
        refreshListing,
        requestListingRefresh,
        syncToolbarState
    };
});
