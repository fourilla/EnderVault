(() => {
    "use strict";

    const FILE_OPEN_ENDPOINT = "/files/open";
    const FILE_PREVIEW_ENDPOINT = "/files/preview";
    const SAFE_LINK_PROTOCOLS = new Set(["http:", "https:", "mailto:"]);
    const SAFE_IMAGE_PROTOCOLS = new Set(["http:", "https:"]);
    const ALERT_PATTERN = /^\s*\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]\s*/i;
    const SPECIAL_FENCE_LANGUAGES = new Set(["mermaid", "math", "latex", "tex"]);
    const MAX_HIGHLIGHT_SOURCE_CHARS = 250_000;
    const MAX_MERMAID_BLOCKS = 24;
    const MAX_MERMAID_SOURCE_CHARS = 100_000;
    const MAX_MATH_DOCUMENT_CHARS = 1_000_000;
    let renderer = null;
    let mermaidInitialized = false;
    let mermaidSequence = 0;

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
            breaks: false,
            highlight: highlightCode
        });
        if (!window.EnderVaultMarkdownMathPlugin?.install) {
            throw new Error("Markdown math extension is unavailable.");
        }
        window.EnderVaultMarkdownMathPlugin.install(renderer);
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

    const highlightCode = (source, language) => {
        const normalizedLanguage = normalizeFenceLanguage(language);
        if (!normalizedLanguage
            || SPECIAL_FENCE_LANGUAGES.has(normalizedLanguage)
            || source.length > MAX_HIGHLIGHT_SOURCE_CHARS
            || !window.hljs?.getLanguage(normalizedLanguage)) {
            return "";
        }

        try {
            return window.hljs.highlight(source, {
                language: normalizedLanguage,
                ignoreIllegals: true
            }).value;
        } catch (error) {
            return "";
        }
    };

    const normalizeFenceLanguage = (language) => (language || "")
        .trim()
        .split(/\s+/, 1)[0]
        .toLowerCase()
        .replace(/[^a-z0-9_+-]/g, "");

    const renderInto = async (target, source, sourcePath = "") => {
        if (!(target instanceof Element)) {
            throw new Error("Markdown preview target is unavailable.");
        }

        clearMathTypesetting(target);
        const markdownSource = source || "";
        const rendered = markdownRenderer().render(markdownSource);
        const fragment = window.DOMPurify.sanitize(rendered, {
            RETURN_DOM_FRAGMENT: true,
            USE_PROFILES: { html: true },
            FORBID_TAGS: ["form", "button", "textarea", "select", "option", "style"],
            FORBID_ATTR: ["style"]
        });

        secureTaskListInputs(fragment);
        wrapTables(fragment);
        addHeadingAnchors(fragment);
        enhanceGithubAlerts(fragment);
        rewriteLinks(fragment, sourcePath);
        rewriteImages(fragment, sourcePath);
        prepareSpecialFences(fragment);
        target.replaceChildren(fragment);

        await renderMermaidBlocks(target);
        await typesetMath(target, markdownSource, sourcePath);
    };

    const prepareSpecialFences = (fragment) => {
        fragment.querySelectorAll("pre > code[class*='language-']").forEach((code) => {
            const languageClass = Array.from(code.classList)
                .find((className) => className.startsWith("language-"));
            const language = normalizeFenceLanguage(languageClass?.slice("language-".length));
            const pre = code.parentElement;
            if (!pre) {
                return;
            }

            if (language === "mermaid") {
                pre.classList.add("markdown-mermaid-source");
                pre.dataset.markdownMermaid = "true";
                return;
            }
            if (language === "math" || language === "latex" || language === "tex") {
                const mathBlock = document.createElement("div");
                mathBlock.className = "markdown-math-block";
                mathBlock.dataset.markdownMath = "true";
                mathBlock.textContent = `\\[\n${code.textContent || ""}\n\\]`;
                pre.replaceWith(mathBlock);
            }
        });
    };

    const renderMermaidBlocks = async (target) => {
        const blocks = Array.from(target.querySelectorAll("[data-markdown-mermaid]"));
        if (blocks.length === 0) {
            return;
        }

        let mermaid;
        try {
            if (!window.mermaid && window.EnderVaultMarkdownMermaidLoader) {
                await window.EnderVaultMarkdownMermaidLoader();
            }
            mermaid = mermaidApi();
            initializeMermaid(mermaid);
        } catch (error) {
            blocks.forEach((block) => showExtensionFailure(
                block,
                "Mermaid renderer is unavailable. The diagram source is shown below."
            ));
            return;
        }

        for (const [index, block] of blocks.entries()) {
            if (index >= MAX_MERMAID_BLOCKS) {
                showExtensionFailure(
                    block,
                    `Only the first ${MAX_MERMAID_BLOCKS} Mermaid diagrams are rendered per preview.`
                );
                continue;
            }

            const source = block.textContent || "";
            if (source.length > MAX_MERMAID_SOURCE_CHARS) {
                showExtensionFailure(
                    block,
                    `This Mermaid block exceeds the ${MAX_MERMAID_SOURCE_CHARS.toLocaleString()} character limit.`
                );
                continue;
            }

            const container = document.createElement("div");
            container.className = "markdown-mermaid mathjax_ignore";
            container.setAttribute("role", "img");
            container.setAttribute("aria-label", "Mermaid diagram");
            block.replaceWith(container);

            try {
                const diagramId = `endervault-mermaid-${++mermaidSequence}`;
                const rendered = await mermaid.render(diagramId, source);
                const diagram = window.DOMPurify.sanitize(rendered.svg, {
                    RETURN_DOM_FRAGMENT: true,
                    USE_PROFILES: { svg: true, svgFilters: true },
                    FORBID_TAGS: ["foreignObject", "script"],
                    FORBID_ATTR: ["onerror", "onload", "onclick"]
                });
                container.replaceChildren(diagram);
            } catch (error) {
                container.replaceWith(block);
                showExtensionFailure(
                    block,
                    "Mermaid diagram could not be rendered. Check the diagram syntax."
                );
            }
        }
    };

    const mermaidApi = () => {
        const api = window.mermaid
            || window.__esbuild_esm_mermaid_nm?.mermaid?.default;
        if (!api || typeof api.initialize !== "function" || typeof api.render !== "function") {
            throw new Error("Mermaid renderer is unavailable.");
        }
        return api;
    };

    const initializeMermaid = (mermaid) => {
        if (mermaidInitialized) {
            return;
        }
        mermaid.initialize({
            startOnLoad: false,
            securityLevel: "strict",
            suppressErrorRendering: true,
            theme: "dark",
            htmlLabels: false
        });
        mermaidInitialized = true;
    };

    const typesetMath = async (target, source, sourcePath) => {
        if (!containsMathNotation(target, source)) {
            return;
        }
        if (source.length > MAX_MATH_DOCUMENT_CHARS) {
            appendExtensionNotice(
                target,
                `Math rendering was skipped because this document exceeds ${MAX_MATH_DOCUMENT_CHARS.toLocaleString()} characters.`
            );
            return;
        }

        let mathJax = window.MathJax;
        if (!mathJax?.startup?.promise && window.EnderVaultMarkdownMathLoader) {
            try {
                mathJax = await window.EnderVaultMarkdownMathLoader();
            } catch (error) {
                appendExtensionNotice(target, "MathJax is unavailable. Math source is shown as text.");
                return;
            }
        }
        if (!mathJax?.startup?.promise) {
            appendExtensionNotice(target, "MathJax is unavailable. Math source is shown as text.");
            return;
        }

        try {
            await mathJax.startup.promise;
            if (typeof mathJax.typesetPromise !== "function") {
                throw new Error("MathJax typesetting API is unavailable.");
            }
            await mathJax.typesetPromise([target]);
            rewriteLinks(target, sourcePath);
        } catch (error) {
            appendExtensionNotice(target, "One or more math expressions could not be rendered.");
        }
    };

    const containsMathNotation = (target, source) =>
        target.querySelector("[data-markdown-math]")
        || source.includes("$$")
        || source.includes("\\(")
        || source.includes("\\[")
        || /(^|[^\\])\$[^$\r\n]+\$/m.test(source);

    const clearMathTypesetting = (target) => {
        if (typeof window.MathJax?.typesetClear === "function") {
            window.MathJax.typesetClear([target]);
        }
    };

    const showExtensionFailure = (sourceBlock, message) => {
        const wrapper = document.createElement("div");
        wrapper.className = "markdown-extension-fallback";
        const notice = createExtensionNotice(message);
        sourceBlock.replaceWith(wrapper);
        wrapper.append(notice, sourceBlock);
    };

    const appendExtensionNotice = (target, message) => {
        target.append(createExtensionNotice(message));
    };

    const createExtensionNotice = (message) => {
        const notice = document.createElement("p");
        notice.className = "markdown-extension-notice";
        notice.textContent = message;
        return notice;
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

    const wrapTables = (fragment) => {
        fragment.querySelectorAll("table").forEach((table) => {
            const wrapper = document.createElement("div");
            wrapper.className = "markdown-table-scroll";
            table.replaceWith(wrapper);
            wrapper.append(table);
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
            const resolved = resolveDestination(destination, sourcePath, SAFE_LINK_PROTOCOLS, FILE_OPEN_ENDPOINT);
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
            const resolved = resolveDestination(destination, sourcePath, SAFE_IMAGE_PROTOCOLS, FILE_PREVIEW_ENDPOINT);
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

    const resolveDestination = (destination, sourcePath, allowedProtocols, vaultEndpoint) => {
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
        const url = vaultFileUrl(vaultEndpoint, relative.path);
        if (relative.fragment) {
            url.hash = relative.fragment;
        }
        return { url: `${url.pathname}${url.search}${url.hash}`, sameDocument: false };
    };

    const vaultFileUrl = (endpoint, path) => {
        const url = new URL(endpoint, window.location.origin);
        if (endpoint === FILE_PREVIEW_ENDPOINT) {
            const separator = path.lastIndexOf("/");
            const parentPath = separator < 0 ? "" : path.slice(0, separator);
            const name = separator < 0 ? path : path.slice(separator + 1);
            if (parentPath) {
                url.searchParams.set("path", parentPath);
            }
            url.searchParams.set("item", name);
            return url;
        }
        url.searchParams.set("path", path);
        return url;
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

    const initializeStandalonePreview = async (container) => {
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
            await renderInto(
                target,
                source.value || source.textContent || "",
                container.dataset.markdownSourcePath || ""
            );
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
