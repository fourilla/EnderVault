import MarkdownIt from 'markdown-it';
import markdownItTaskLists from 'markdown-it-task-lists';
import markdownItFootnote from 'markdown-it-footnote';
import DOMPurify from 'dompurify';
import hljs from 'highlight.js';
import 'highlight.js/styles/github-dark.css';
import '../../../src/main/resources/static/js/markdown-math-plugin.js';
import '../../../src/main/resources/static/js/mathjax-config.js';
import '../../../src/main/resources/static/js/markdown-renderer.js';

window.markdownit = MarkdownIt;
window.markdownitTaskLists = markdownItTaskLists;
window.markdownitFootnote = markdownItFootnote;
window.DOMPurify = DOMPurify;
window.hljs = hljs;

let mermaidPromise: Promise<any> | null = null;
window.EnderVaultMarkdownMermaidLoader = () => {
  if (!mermaidPromise) {
    mermaidPromise = import('mermaid').then((module) => {
      window.mermaid = module.default;
      return module.default;
    });
  }
  return mermaidPromise;
};

let mathJaxPromise: Promise<any> | null = null;
window.EnderVaultMarkdownMathLoader = () => {
  if (window.MathJax?.startup?.promise) {
    return Promise.resolve(window.MathJax);
  }
  if (!mathJaxPromise) {
    mathJaxPromise = new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = '/_static/mathjax/4.1.1/tex-chtml.js';
      script.async = true;
      script.addEventListener('load', () => resolve(window.MathJax), { once: true });
      script.addEventListener('error', () => reject(new Error('MathJax could not be loaded.')), { once: true });
      document.head.append(script);
    });
  }
  return mathJaxPromise;
};
