import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

const engineSource = readFileSync(new URL('../../src/main/resources/static/js/context-menu.js', import.meta.url), 'utf8');
const pageSource = readFileSync(new URL('../../src/main/resources/static/js/page-context-menu.js', import.meta.url), 'utf8');

test('file tools exclude preview surfaces, not their surrounding panel or toolbar', () => {
  const source = (path) => readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8');
  const tools = source('file-detail/FileTools.tsx');
  assert.doesNotMatch(tools, /<section[^>]*data-native-context-menu/);
  assert.equal((tools.match(/className="preview-surface" data-native-context-menu/g) || []).length, 2);
  for (const [path, className] of [
    ['shared/file-tools/ImageViewer.tsx', 'image-viewer-stage'],
    ['shared/file-tools/ComicViewer.tsx', 'comic-stage'],
    ['file-detail/TextTool.tsx', 'markdown-editor-preview'],
    ['file-detail/ArchiveTool.tsx', 'archive-tree'],
  ]) {
    assert.match(source(path), new RegExp(`className="${className}"[^>]*data-native-context-menu`));
  }
  assert.ok(engineSource.includes('.CodeMirror'));
});

function setup() {
  let selection = null;
  const window = { getSelection: () => selection };
  vm.runInNewContext(engineSource, { window });
  const event = (inside = true, excluded = false) => ({ target: {
    closest: (selector) => selector === '.app-main' ? (inside ? {} : null) : (excluded ? {} : null),
  } });
  return { window, event, select: (value) => { selection = value; } };
}

test('common page context accepts app-main space but preserves controls, tools and selected text', () => {
  const { window, event, select } = setup();
  const resolve = window.EnderVaultContextMenus.pageContextForEvent;
  assert.equal(resolve(event()).mode, 'page-background');
  assert.equal(resolve(event(false)), null);
  assert.equal(resolve(event(true, true)), null);
  assert.equal(resolve({ target: null }), null);
  select({ isCollapsed: false, toString: () => 'selected text' });
  assert.equal(resolve(event()), null);
});

for (const readyState of ['loading', 'complete']) {
  test(`fallback initializes without initial app-main and yields to mounted page menus (${readyState})`, () => {
    const { window, event } = setup();
    let listener;
    let options;
    let owner = '';
    let enabled = true;
    Object.assign(window.EnderVaultContextMenus, {
      createActionMenu: (value) => { options = value; },
      pageScopeOwner: () => owner,
      globalActionsFor: () => enabled ? [{}] : [],
    });
    const document = { readyState, addEventListener: (_, callback) => { listener = callback; } };
    vm.runInNewContext(pageSource, { window, document });
    if (readyState === 'loading') listener();
    assert.equal(options.contextForEvent(event()).mode, 'page-background');
    owner = 'file-detail';
    assert.equal(options.contextForEvent(event()), null);
    owner = '';
    enabled = false;
    assert.equal(options.contextForEvent(event()), null);
    assert.ok(options.extraCloseEvents.includes('endervault:spa-shell-ready'));
  });
}
