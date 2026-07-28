(() => {
    "use strict";

    const MATHJAX_VERSION = "4.1.1";
    const FONT_VERSION = "4.1.1";
    const mathjaxRoot = `/_static/mathjax/${MATHJAX_VERSION}`;
    const fontRoot = `/_static/at/mathjax/mathjax-newcm-font/${FONT_VERSION}`;

    window.MathJax = {
        loader: {
            load: ["ui/safe"],
            paths: {
                mathjax: mathjaxRoot
            }
        },
        tex: {
            inlineMath: [["$", "$"], ["\\(", "\\)"]],
            displayMath: [["$$", "$$"], ["\\[", "\\]"]],
            processEscapes: true
        },
        chtml: {
            fontURL: `${fontRoot}/chtml/woff2`,
            dynamicPrefix: `${fontRoot}/chtml/dynamic`
        },
        options: {
            safeOptions: {
                allow: {
                    URLs: "safe",
                    classes: "safe",
                    cssIDs: "safe",
                    styles: "safe"
                }
            },
            skipHtmlTags: ["script", "noscript", "style", "textarea", "pre", "code"]
        },
        startup: {
            typeset: false
        }
    };
})();
