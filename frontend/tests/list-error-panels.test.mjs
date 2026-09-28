import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import * as React from 'react';
import * as jsx from 'react/jsx-runtime';
import { renderToStaticMarkup } from 'react-dom/server';

const pages = [
  { file: 'favorites/FavoritesApp', name: 'FavoritesApp', snapshot: { items: [] }, errorSlot: 2, tokenSlot: 3 },
  { file: 'trash/TrashApp', name: 'TrashApp', snapshot: { items: [] }, errorSlot: 2, tokenSlot: 3 },
  { file: 'shares/SharedLinksApp', name: 'SharedLinksApp', snapshot: [], errorSlot: 1, tokenSlot: 2 },
];

async function compile(file) {
  const result = await build({ configFile: false, logLevel: 'silent', build: {
    write: false, minify: false,
    lib: { entry: fileURLToPath(new URL(`../src/${file}.tsx`, import.meta.url)), formats: ['cjs'] },
    rolldownOptions: { external: (_id, importer) => Boolean(importer) },
  } });
  return (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;
}

function evaluate(code, dependencies) {
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, require(id) {
    if (id === 'react/jsx-runtime') return jsx;
    if (id.endsWith('.css')) return {};
    return dependencies(id);
  } });
  return module.exports;
}

const { PageErrorPanel } = evaluate(await compile('shared/layout/PageErrorPanel'), id => {
  assert.equal(id, 'react');
  return React;
});

for (const page of pages) {
  const code = await compile(page.file);
  test(`${page.name} shares initial/stale error layout and retries without clearing the snapshot`, () => {
    for (const snapshot of [null, page.snapshot]) {
      const states = [snapshot, false, '', 0, ''];
      states[page.errorSlot] = 'Network unavailable';
      states[page.tokenSlot] = 0;
      let index = 0;
      const { [page.name]: App } = evaluate(code, id => {
        if (id === 'react') return {
          useEffect() {},
          useState(initial) {
            const slot = index++;
            if (!(slot in states)) states[slot] = initial;
            return [states[slot], value => { states[slot] = typeof value === 'function' ? value(states[slot]) : value; }];
          },
        };
        if (id === 'react-router-dom') return { Link: ({ children }) => React.createElement('a', null, children) };
        if (id.endsWith('/PageErrorPanel')) return { PageErrorPanel };
        if (id.endsWith('/PageHeader')) return { PageHeader: () => null };
        if (id.endsWith('/FloatingPageActions')) return { FloatingPageActions: () => null };
        if (id.endsWith('/BrowserEntries')) return { icon: value => React.createElement('i', { className: value }) };
        if (id.endsWith('-api')) return {};
        throw new Error(`Unexpected dependency: ${id}`);
      });
      const tree = App();
      const findPanel = node => {
        if (!React.isValidElement(node)) return undefined;
        if (node.type === PageErrorPanel) return node;
        return React.Children.toArray(node.props.children).map(findPanel).find(Boolean);
      };
      const panel = findPanel(tree);
      assert.ok(panel);
      assert.equal(panel.props.stale, snapshot !== null);
      const html = renderToStaticMarkup(tree);
      assert.match(html, /page-feedback-layout/);
      assert.match(html, /Network unavailable/);
      assert.equal(html.includes('Showing the last loaded values.'), snapshot !== null);
      assert.match(html, /class="icon-text-button"/);
      panel.props.actions.props.onClick();
      assert.equal(states[page.tokenSlot], 1);
      assert.equal(states[0], snapshot);
    }
  });
}
