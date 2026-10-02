import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';
import { Worker } from 'node:worker_threads';
import MarkdownIt from 'markdown-it';
import taskLists from 'markdown-it-task-lists';
import footnote from 'markdown-it-footnote';

const mathPlugin = readFileSync(new URL('../../src/main/resources/static/js/markdown-math-plugin.js', import.meta.url), 'utf8');
const context = { window: {} };
vm.runInNewContext(mathPlugin, context);

function renderer() {
  const markdown = new MarkdownIt({ html: false, linkify: true, typographer: false, breaks: false });
  context.window.EnderVaultMarkdownMathPlugin.install(markdown);
  return markdown.use(taskLists, { enabled: false, label: false }).use(footnote);
}

test('Markdown dependency update preserves links, tables and normal text rendering', () => {
  const html = renderer().render('# Notes\n\n**Bold** and ~~old~~ https://example.com a@b.co\n\n| A | B |\n| --- | --- |\n| 1 | 2 |');
  for (const expected of ['<h1>Notes</h1>', '<strong>Bold</strong>', '<s>old</s>',
    'href="https://example.com"', 'href="mailto:a@b.co"', '<table>', '<td>2</td>']) {
    assert.ok(html.includes(expected), expected);
  }
});

test('Markdown still escapes raw HTML and rejects executable link destinations', () => {
  const html = renderer().render('<script>alert(1)</script>\n\n[bad](javascript:alert(1))\n\n[bad](data:text/html,hello)');
  assert.ok(html.includes('&lt;script&gt;'));
  assert.doesNotMatch(html, /<script\b|href="(?:javascript:|data:text\/html)/i);
});

test('task list and footnote plugins remain compatible and checklist controls remain disabled', () => {
  const html = renderer().render('- [x] Done\n- [ ] Later\n\nText[^a]\n\n[^a]: Footnote');
  assert.match(html, /class="task-list-item-checkbox"[^>]*checked/);
  assert.match(html, /disabled="" type="checkbox"/);
  assert.match(html, /class="footnote-ref"/);
  assert.match(html, /class="footnotes"/);
  assert.ok(html.includes('Footnote'));
});

test('custom math rules preserve TeX and escape markup inside math spans', () => {
  const html = renderer().render('Inline $x^2$ and \\(a+b\\)\n\n$$\n\\frac{1}{2}\n$$\n\n$<img>$');
  assert.ok(html.includes('<span class="markdown-math-inline">$x^2$</span>'));
  assert.ok(html.includes('\\(a+b\\)'));
  assert.match(html, /class="markdown-math-block"/);
  assert.ok(html.includes('\\frac{1}{2}'));
  assert.ok(html.includes('&lt;img&gt;'));
});

for (const kind of ['emails', 'unknown schemes']) {
  test(`linkify handles long ${kind} without the reported quadratic stall`, async () => {
    // Isolate hostile inputs so a parser regression cannot hang the test runner.
    const worker = new Worker(`
      const { parentPort, workerData } = require('node:worker_threads');
      import(${JSON.stringify(import.meta.resolve('markdown-it'))}).then(({ default: MarkdownIt }) => {
        const source = workerData === 'emails' ? 'a@b.co\\n'.repeat(40000) : 'a://'.repeat(40000);
        const html = new MarkdownIt({ html: false, linkify: true }).render(source);
        parentPort.postMessage({ length: html.length, linked: html.includes('mailto:a@b.co') });
      }).catch(error => { throw error; });
    `, { eval: true, workerData: kind });
    let timer;
    try {
      const result = await new Promise((resolve, reject) => {
        timer = setTimeout(() => reject(new Error('Long Markdown linkification exceeded 10 seconds.')), 10000);
        worker.once('message', resolve);
        worker.once('error', reject);
        worker.once('exit', (code) => reject(new Error(`Markdown worker exited without a result: ${code}`)));
      });
      assert.ok(result.length > 0);
      assert.equal(result.linked, kind === 'emails');
    } finally {
      clearTimeout(timer);
      await worker.terminate();
    }
  });
}
