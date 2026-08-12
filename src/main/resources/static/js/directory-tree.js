(() => {
    const normalizePath = (value) => String(value ?? "")
            .trim()
            .replaceAll("\\", "/")
            .replace(/^\/+|\/+$/g, "");

    class DirectoryTree {
        constructor(root, options = {}) {
            if (!root || typeof options.loadEntries !== "function") {
                throw new TypeError("DirectoryTree requires a root element and a loadEntries function.");
            }

            this.root = root;
            this.loadEntries = options.loadEntries;
            this.onSelect = options.onSelect ?? (() => {});
            this.onError = options.onError ?? (() => {});
            this.rootLabel = options.rootLabel ?? "Vault root";
            this.selectedPath = "";
            this.root.classList.add("directory-tree");
        }

        selection() {
            return this.selectedPath;
        }

        async render(path = "") {
            const initialPath = normalizePath(path);
            this.root.replaceChildren();

            const list = this.createList("tree");
            list.append(this.createNode({ name: this.rootLabel, path: "" }));
            this.root.append(list);
            this.updateSelection(initialPath, null);
            await this.revealPath(initialPath);
        }

        createList(role = "group") {
            const list = document.createElement("ul");
            list.className = "directory-tree-list";
            list.setAttribute("role", role);
            if (role === "group") {
                list.hidden = true;
            }
            return list;
        }

        createNode(entry) {
            const path = normalizePath(entry.path);
            const node = document.createElement("li");
            node.className = "directory-tree-node";
            node.dataset.path = path;
            node.setAttribute("role", "treeitem");
            node.setAttribute("aria-expanded", "false");

            const row = document.createElement("div");
            row.className = "directory-tree-row";
            row.classList.toggle("is-hidden-item", Boolean(entry.hidden));

            const toggle = document.createElement("button");
            toggle.className = "directory-tree-toggle";
            toggle.type = "button";
            toggle.dataset.label = entry.name;
            toggle.title = `Expand ${entry.name}`;
            toggle.setAttribute("aria-label", `Expand ${entry.name}`);
            toggle.innerHTML = '<i class="fas fa-chevron-right" aria-hidden="true"></i>';

            const select = document.createElement("button");
            select.className = "directory-tree-select";
            select.type = "button";
            select.title = path ? `/${path}` : "/";
            select.innerHTML = '<i class="fas fa-folder" aria-hidden="true"></i>';

            const label = document.createElement("span");
            label.textContent = entry.name;
            select.append(label);

            const children = this.createList();
            toggle.addEventListener("click", () => this.toggleNode(node, children));
            select.addEventListener("click", () => this.updateSelection(path, select));
            select.addEventListener("dblclick", (event) => {
                event.preventDefault();
                this.toggleNode(node, children);
            });
            row.append(toggle, select);
            node.append(row, children);
            return node;
        }

        updateSelection(path, button) {
            this.selectedPath = normalizePath(path);
            this.root.querySelectorAll(".directory-tree-select.selected").forEach((item) => {
                item.classList.remove("selected");
                item.removeAttribute("aria-current");
            });
            button?.classList.add("selected");
            button?.setAttribute("aria-current", "true");
            this.onSelect(this.selectedPath);
        }

        async loadChildren(node, children) {
            if (node.dataset.loaded === "true") {
                return;
            }

            node.dataset.loading = "true";
            node.setAttribute("aria-busy", "true");
            try {
                const entries = await this.loadEntries(node.dataset.path);
                entries.forEach((entry) => children.append(this.createNode(entry)));
                node.dataset.loaded = "true";
                if (entries.length === 0) {
                    node.classList.add("empty");
                }
            } finally {
                delete node.dataset.loading;
                node.removeAttribute("aria-busy");
            }
        }

        async toggleNode(node, children, expandOnly = false) {
            if (node.dataset.loading === "true") {
                return;
            }

            const expanded = node.classList.contains("expanded");
            if (expanded && !expandOnly) {
                node.classList.remove("expanded");
                node.setAttribute("aria-expanded", "false");
                const toggle = node.querySelector(":scope > .directory-tree-row .directory-tree-toggle");
                toggle.title = `Expand ${toggle.dataset.label}`;
                toggle.setAttribute("aria-label", toggle.title);
                children.hidden = true;
                return;
            }

            try {
                await this.loadChildren(node, children);
                node.classList.add("expanded");
                node.setAttribute("aria-expanded", "true");
                const toggle = node.querySelector(":scope > .directory-tree-row .directory-tree-toggle");
                toggle.title = `Collapse ${toggle.dataset.label}`;
                toggle.setAttribute("aria-label", toggle.title);
                children.hidden = false;
            } catch (error) {
                this.onError(error);
            }
        }

        async revealPath(path) {
            const segments = normalizePath(path).split("/").filter(Boolean);
            let node = this.root.querySelector('.directory-tree-node[data-path=""]');
            if (!node) {
                return;
            }

            await this.toggleNode(node, node.lastElementChild, true);
            let currentPath = "";
            for (const segment of segments) {
                currentPath = currentPath ? `${currentPath}/${segment}` : segment;
                const child = Array.from(node.lastElementChild.children)
                        .find((item) => item.dataset.path === currentPath);
                if (!child) {
                    return;
                }
                node = child;
                await this.toggleNode(node, node.lastElementChild, true);
            }

            const select = node.querySelector(":scope > .directory-tree-row .directory-tree-select");
            this.updateSelection(node.dataset.path, select);
            select?.scrollIntoView({ block: "nearest" });
        }
    }

    window.EnderVaultDirectoryTree = {
        create(root, options) {
            return new DirectoryTree(root, options);
        },
        normalizePath
    };
})();
