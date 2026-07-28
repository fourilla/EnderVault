(() => {
    "use strict";

    const INLINE_DELIMITERS = [
        { open: "$$", close: "$$", kind: "dollars" },
        { open: "\\(", close: "\\)", kind: "brackets" },
        { open: "$", close: "$", kind: "dollars" }
    ];
    const BLOCK_DELIMITERS = [
        { open: "\\[", close: "\\]" },
        { open: "$$", close: "$$" }
    ];

    const install = (markdown) => {
        markdown.inline.ruler.before("escape", "endervault_math_inline", inlineMathRule);
        markdown.block.ruler.after("blockquote", "endervault_math_block", blockMathRule, {
            alt: ["paragraph", "reference", "blockquote", "list"]
        });

        markdown.renderer.rules.endervault_math_inline = (tokens, index) => {
            const content = markdown.utils.escapeHtml(tokens[index].content);
            const delimiter = tokens[index].meta;
            return `<span class="markdown-math-inline">${delimiter.open}${content}${delimiter.close}</span>`;
        };
        markdown.renderer.rules.endervault_math_block = (tokens, index) => {
            const content = markdown.utils.escapeHtml(tokens[index].content);
            const delimiter = tokens[index].meta;
            return `<div class="markdown-math-block" data-markdown-math="true">`
                + `${delimiter.open}\n${content}\n${delimiter.close}</div>\n`;
        };
    };

    const inlineMathRule = (state, silent) => {
        const start = state.pos;
        const delimiter = INLINE_DELIMITERS.find(({ open }) =>
            state.src.startsWith(open, start)
        );
        if (!delimiter || isEscaped(state.src, start)) {
            return false;
        }

        const contentStart = start + delimiter.open.length;
        const closeAt = findClosingDelimiter(state.src, delimiter.close, contentStart);
        if (closeAt < 0 || closeAt === contentStart) {
            return false;
        }

        const content = state.src.slice(contentStart, closeAt);
        if (content.includes("\n") || !validInlinePadding(content, delimiter.kind, state.src, closeAt)) {
            return false;
        }

        if (!silent) {
            const token = state.push("endervault_math_inline", "math", 0);
            token.content = content;
            token.markup = delimiter.open;
            token.meta = delimiter;
        }
        state.pos = closeAt + delimiter.close.length;
        return true;
    };

    const validInlinePadding = (content, kind, source, closeAt) => {
        if (/^\s|\s$/.test(content)) {
            return false;
        }
        return kind !== "dollars" || !/\d/.test(source.charAt(closeAt + 1));
    };

    const findClosingDelimiter = (source, close, from) => {
        let index = source.indexOf(close, from);
        while (index >= 0) {
            if (!isEscaped(source, index)) {
                return index;
            }
            index = source.indexOf(close, index + close.length);
        }
        return -1;
    };

    const isEscaped = (source, index) => {
        let backslashes = 0;
        for (let position = index - 1; position >= 0 && source.charAt(position) === "\\"; position--) {
            backslashes++;
        }
        return backslashes % 2 === 1;
    };

    const blockMathRule = (state, startLine, endLine, silent) => {
        const start = state.bMarks[startLine] + state.tShift[startLine];
        const firstLineEnd = state.eMarks[startLine];
        const firstLine = state.src.slice(start, firstLineEnd);
        const delimiter = BLOCK_DELIMITERS.find(({ open }) => firstLine.startsWith(open));
        if (!delimiter) {
            return false;
        }
        if (silent) {
            return true;
        }

        const firstContent = firstLine.slice(delimiter.open.length);
        const sameLineClose = closingPosition(firstContent, delimiter.close);
        if (sameLineClose >= 0) {
            return pushBlockToken(
                state,
                startLine,
                startLine + 1,
                firstContent.slice(0, sameLineClose),
                delimiter
            );
        }

        const contentLines = [firstContent];
        for (let line = startLine + 1; line < endLine; line++) {
            const lineStart = state.bMarks[line] + state.tShift[line];
            const lineEnd = state.eMarks[line];
            const lineContent = state.src.slice(lineStart, lineEnd);
            const closeAt = closingPosition(lineContent, delimiter.close);
            if (closeAt < 0) {
                contentLines.push(lineContent);
                continue;
            }
            if (state.tShift[line] - state.blkIndent >= 4) {
                contentLines.push(lineContent);
                continue;
            }

            contentLines.push(lineContent.slice(0, closeAt));
            return pushBlockToken(
                state,
                startLine,
                line + 1,
                contentLines.join("\n"),
                delimiter
            );
        }
        return false;
    };

    const closingPosition = (line, close) => {
        const trimmedEnd = line.trimEnd();
        return trimmedEnd.endsWith(close)
            ? trimmedEnd.length - close.length
            : -1;
    };

    const pushBlockToken = (state, startLine, nextLine, content, delimiter) => {
        state.line = nextLine;
        const token = state.push("endervault_math_block", "math", 0);
        token.block = true;
        token.content = content.trim();
        token.map = [startLine, nextLine];
        token.markup = delimiter.open;
        token.meta = delimiter;
        return true;
    };

    window.EnderVaultMarkdownMathPlugin = Object.freeze({ install });
})();
