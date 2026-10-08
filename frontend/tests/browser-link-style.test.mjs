import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');

test('table and file-card links share a scoped no-underline hover policy', () => {
  const css = read('../../src/main/resources/static/css/components/browser.css');
  assert.match(css, /\.table-wrap a:hover,\s*\.readonly-table a:hover,\s*\.browser-card a:hover,\s*\.readonly-file-card a:hover\s*\{\s*text-decoration: none;/);
  assert.match(css, /\.item-name,\s*\.table-wrap \.path-link\s*\{\s*font-weight: 600;/);
  const base = read('../../src/main/resources/static/css/base.css');
  assert.match(base, /a:hover\s*\{\s*text-decoration: underline;/);
  const shared = read('../src/public-share/SharedDirectoryPage.tsx');
  assert.match(shared, /className="table-wrap"/);
  const host = read('../../src/main/resources/templates/shared-app.html');
  assert.match(host, /fragments\/assets :: styles/);
  const path = read('../src/shared/browser/path-link.css');
  assert.doesNotMatch(path, /text-decoration/);
  assert.doesNotMatch(path, /font-weight/);
  assert.match(path, /color: var\(--accent-strong\)/);
  assert.match(path, /:focus-visible/);
});
