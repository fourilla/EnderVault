(() => {
    "use strict";

    const PREVIEW_ENDPOINT = "/files/detail/preview";
    const SAFE_LINK_PROTOCOLS = new Set(["http:", "https:", "mailto:"]);
    const SAFE_IMAGE_PROTOCOLS = new Set(["http:", "https:"]);
    const ALERT_PATTERN = /^\s*\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]\s*/i;
    let renderer = null;

    const markdownRenderer = () => {
        if (renderer) {
            return renderer;
        }
        if (typeof window.markdownit !== "function" || !window.DOMPurify) {
            throw new Error("Markdown renderer dependencies are unavailable.");
        }

        renderer = window.markdownit({
            html: false,
            linkify: true,
            typographer: false,
            breaks: false
        });
        if (typeof window.markdownitTaskLists === "function") {
            renderer.use(window.markdownitTaskLists, {
                enabled: false,
                label: false
            });
        }
        if (typeof window.markdownitFootnote === "function") {
            renderer.use(window.markdownitFootnote);
        }
        return renderer;
    };

    const renderInto = (target, source, sourcePath = "") => {
        if (!(target instanceof Element)) {
            throw new Error("Markdown preview target is unavailable.");
        }

        const rendered = markdownRenderer().render(source || "");
        const fragment = window.DOMPurify.sanitize(rendered, {
            RETURN_DOM_FRAGMENT: true,
            USE_PROFILES: { html: true },
            FORBID_TAGS: ["form", "button", "textarea", "select", "option", "style"],
            FORBID_ATTR: ["style"]
        });

        secureTaskListInputs(fragment);
        addHeadingAnchors(fragment);
        enhanceGithubAlerts(fragment);
        rewriteLinks(fragment, sourcePath);
        rewriteImages(fragment, sourcePath);
        target.replaceChildren(fragment);
    };

    const secureTaskListInputs = (fragment) => {
        fragment.querySelectorAll("input").forEach((input) => {
            if (input.type !== "checkbox") {
                input.remove();
                return;
            }
            input.disabled = true;
            input.removeAttribute("name");
            input.removeAttribute("value");
        });
    };

    const addHeadingAnchors = (fragment) => {
        const usedIds = new Map();
        fragment.querySelectorAll("h1, h2, h3, h4, h5, h6").forEach((heading) => {
            const base = headingSlug(heading.textContent);
            if (!base) {
                return;
            }
            const duplicateIndex = usedIds.get(base) || 0;
            usedIds.set(base, duplicateIndex + 1);
            heading.id = duplicateIndex === 0 ? base : `${base}-${duplicateIndex}`;
        });
    };

    const headingSlug = (value) => (value || "")
        .trim()
        .toLowerCase()
        .replace(/[^\p{L}\p{N}\s_-]/gu, "")
        .replace(/\s+/g, "-")
        .replace(/-+/g, "-")
        .replace(/^-|-$/g, "");

    const enhanceGithubAlerts = (fragment) => {
        fragment.querySelectorAll("blockquote").forEach((blockquote) => {
            const firstParagraph = blockquote.firstElementChild;
            if (!firstParagraph || firstParagraph.tagName !== "P") {
                return;
            }

            const markerNode = firstMatchingTextNode(firstParagraph, ALERT_PATTERN);
            const match = markerNode?.nodeValue?.match(ALERT_PATTERN);
            if (!match) {
                return;
            }

            const type = match[1].toLowerCase();
            markerNode.nodeValue = markerNode.nodeValue.slice(match[0].length);
            blockquote.classList.add("markdown-alert", `markdown-alert-${type}`);

            const title = document.createElement("p");
            title.className = "markdown-alert-title";
            title.textContent = type.charAt(0).toUpperCase() + type.slice(1);
            blockquote.prepend(title);
        });
    };

    const firstMatchingTextNode = (root, pattern) => {
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        let node = walker.nextNode();
        while (node) {
            if (pattern.test(node.nodeValue || "")) {
                return node;
            }
            if ((node.nodeValue || "").trim()) {
                return null;
            }
            node = walker.nextNode();
        }
        return null;
    };

    const rewriteLinks = (fragment, sourcePath) => {
        fragment.querySelectorAll("a[href]").forEach((link) => {
            const destination = link.getAttribute("href");
            const resolved = resolveDestination(destination, sourcePath, SAFE_LINK_PROTOCOLS);
            if (!resolved) {
                link.removeAttribute("href");
                link.removeAttribute("target");
                link.removeAttribute("rel");
                return;
            }

            link.setAttribute("href", resolved.url);
            if (resolved.sameDocument) {
                link.removeAttribute("target");
                link.removeAttribute("rel");
                return;
            }
            link.setAttribute("target", "_blank");
            link.setAttribute("rel", "noopener noreferrer");
        });
    };

    const rewriteImages = (fragment, sourcePath) => {
        fragment.querySelectorAll("img[src]").forEach((image) => {
            const destination = image.getAttribute("src");
            const resolved = resolveDestination(destination, sourcePath, SAFE_IMAGE_PROTOCOLS);
            if (!resolved || resolved.sameDocument) {
                image.remove();
                return;
            }

            image.setAttribute("src", resolved.url);
            image.setAttribute("loading", "lazy");
            image.setAttribute("decoding", "async");
            image.setAttribute("referrerpolicy", "no-referrer");
        });
    };

    const resolveDestination = (destination, sourcePath, allowedProtocols) => {
        const normalized = (destination || "").trim();
        if (!normalized) {
            return null;
        }
        if (normalized.startsWith("#")) {
            return { url: normalized, sameDocument: true };
        }
        if (normalized.startsWith("/") && !normalized.startsWith("//")) {
            try {
                const sameOrigin = new URL(normalized, window.location.origin);
                if (sameOrigin.origin !== window.location.origin) {
                    return null;
                }
                return {
                    url: `${sameOrigin.pathname}${sameOrigin.search}${sameOrigin.hash}`,
                    sameDocument: false
                };
            } catch (error) {
                return null;
            }
        }

        const schemeMatch = normalized.match(/^([a-z][a-z0-9+.-]*):/i);
        if (schemeMatch || normalized.startsWith("//")) {
            try {
                const absolute = new URL(normalized, window.location.href);
                if (!allowedProtocols.has(absolute.protocol)) {
                    return null;
                }
                return { url: absolute.href, sameDocument: false };
            } catch (error) {
                return null;
            }
        }

        const relative = resolveVaultRelativePath(sourcePath, normalized);
        if (!relative) {
            return null;
        }
        const url = new URL(PREVIEW_ENDPOINT, window.location.origin);
        url.searchParams.set("path", relative.path);
        if (relative.fragment) {
            url.hash = relative.fragment;
        }
        return { url: `${url.pathname}${url.search}${url.hash}`, sameDocument: false };
    };

    const resolveVaultRelativePath = (sourcePath, destination) => {
        const hashIndex = destination.indexOf("#");
        const fragment = hashIndex >= 0 ? destination.slice(hashIndex + 1) : "";
        const withoutFragment = hashIndex >= 0 ? destination.slice(0, hashIndex) : destination;
        const queryIndex = withoutFragment.indexOf("?");
        const relativePath = queryIndex >= 0 ? withoutFragment.slice(0, queryIndex) : withoutFragment;
        if (!relativePath) {
            return null;
        }

        const sourceSegments = normalizeSourcePathSegments(sourcePath);
        if (sourceSegments === null) {
            return null;
        }
        sourceSegments.pop();

        for (const rawSegment of relativePath.replaceAll("\\", "/").split("/")) {
            const segment = decodePathSegment(rawSegment);
            if (segment === null) {
                return null;
            }
            if (!segment || segment === ".") {
                continue;
            }
            if (segment === "..") {
                if (sourceSegments.length === 0) {
                    return null;
                }
                sourceSegments.pop();
                continue;
            }
            sourceSegments.push(segment);
        }

        return sourceSegments.length === 0
            ? null
            : { path: sourceSegments.join("/"), fragment };
    };

    const normalizeSourcePathSegments = (path) => {
        const segments = [];
        for (const segment of (path || "").replaceAll("\\", "/").split("/")) {
            if (!segment || segment === ".") {
                continue;
            }
            if (segment === "..") {
                if (segments.length === 0) {
                    return null;
                }
                segments.pop();
                continue;
            }
            segments.push(segment);
        }
        return segments;
    };

    const decodePathSegment = (segment) => {
        try {
            const decoded = decodeURIComponent(segment);
            return decoded.includes("/") || decoded.includes("\\") ? null : decoded;
        } catch (error) {
            return null;
        }
    };

    const initializeStandalonePreview = (container) => {
        const source = container.querySelector("[data-markdown-source]");
        const target = container.querySelector("[data-markdown-document-body]");
        const status = container.querySelector("[data-markdown-document-status]");
        if (!source || !target) {
            return;
        }

        if (status) {
            status.hidden = false;
            status.textContent = "Rendering Markdown...";
        }
        try {
            renderInto(target, source.value || source.textContent || "", container.dataset.markdownSourcePath || "");
            target.hidden = false;
            if (status) {
                status.hidden = true;
            }
        } catch (error) {
            if (status) {
                status.textContent = error.message || "Markdown preview could not be rendered.";
            }
        }
    };

    window.EnderVaultMarkdown = Object.freeze({
        renderInto
    });

    document.addEventListener("DOMContentLoaded", () => {
        document.querySelectorAll("[data-markdown-document]").forEach(initializeStandalonePreview);
    });
})();
