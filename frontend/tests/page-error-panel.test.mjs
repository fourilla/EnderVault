import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import * as React from 'react';
import * as jsx from 'react/jsx-runtime';
import { renderToStaticMarkup } from 'react-dom/server';

const result = await build({ configFile: false, logLevel: 'silent', build: {
  write: false, minify: false,
  lib: { entry: fileURLToPath(new URL('../src/shared/layout/PageErrorPanel.tsx', import.meta.url)), formats: ['cjs'] },
  rolldownOptions: { external: (_id, importer) => Boolean(importer) },
} });
const code = (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;
const module = { exports: {} };
vm.runInNewContext(code, { module, exports: module.exports, require(id) {
  if (id === 'react') return React;
  if (id === 'react/jsx-runtime') return jsx;
  if (id.endsWith('.css')) return {};
  throw new Error(`Unexpected dependency: ${id}`);
} });
const { PageErrorPanel } = module.exports;
const render = props => renderToStaticMarkup(React.createElement(PageErrorPanel, props));

test('page errors share an accessible panel and safely render messages', () => {
  const html = render({ title: 'Unavailable', message: '<script>alert(1)</script>' });
  assert.match(html, /role="alert"/);
  assert.match(html, /aria-labelledby=/);
  assert.match(html, /&lt;script&gt;/);
  assert.doesNotMatch(html, /<script>|page-error-actions|last loaded/);
});

test('stale data and recovery actions are explicit caller choices', () => {
  const html = render({ message: 'Network error', stale: true,
    actions: React.createElement('button', { type: 'button', disabled: true }, 'Retry') });
  assert.match(html, /Showing the last loaded values/);
  assert.match(html, /form-actions end page-error-actions/);
  assert.match(html, /disabled/);
});

test('error spacing belongs to the parent and pending polling clears recovered errors', () => {
  const css = readFileSync(new URL('../src/shared/layout/page-error-panel.css', import.meta.url), 'utf8');
  assert.match(css, /\.page-feedback-layout\s*\{[^}]*gap:/);
  assert.match(css, /overflow-wrap:\s*anywhere/);
  assert.doesNotMatch(css.match(/\.page-error-panel\s*\{([^}]+)\}/)[1], /margin/);
  const pending = readFileSync(new URL('../src/pending-decisions/PendingDecisionsApp.tsx', import.meta.url), 'utf8');
  assert.match(pending, /setSnapshot\(\{ query: activeQuery, decisions: payload.decisions \}\);\s*setFeedback\(null\)/);
  assert.match(pending, /page-feedback-layout/);
  assert.match(pending, /stale=\{decisions !== null\}/);
});

test('page recovery buttons reuse the standard button interaction styles', () => {
  for (const file of ['app/AdminAppContext.tsx', 'app/AdminAppRouter.tsx', 'pending-decisions/PendingDecisionsApp.tsx']) {
    const source = readFileSync(new URL(`../src/${file}`, import.meta.url), 'utf8');
    const recovery = source.match(/<button\b[\s\S]*?<\/button>/g)?.find(button => /<span>(Retry|Reload)<\/span>/.test(button));
    assert.ok(recovery, `${file} must expose a labeled recovery button`);
    assert.match(recovery, /className="icon-text-button"/);
    assert.match(recovery, /fas fa-arrows-rotate/);
    assert.doesNotMatch(recovery, /ghost/);
  }
});
