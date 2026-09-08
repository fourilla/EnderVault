import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import test from 'node:test';
import { build } from 'vite';

const result = await build({ configFile: false, logLevel: 'silent', build: {
  write: false, minify: false,
  lib: { entry: fileURLToPath(new URL('../src/app/FloatingPageActions.tsx', import.meta.url)), formats: ['cjs'] },
  rolldownOptions: { external: (_id, importer) => Boolean(importer) },
} });
const code = (Array.isArray(result) ? result[0] : result).output.find((item) => item.type === 'chunk').code;

function setup(props) {
  let activeId = null, routeKey = 'files', cursor = 0, overlay = false, observerCallback;
  const slots = [], queue = [], listeners = new Map(), focus = [];
  const closeAll = () => { activeId = null; };
  const effect = (run, deps) => {
    const index = cursor++, old = slots[index];
    if (!old || deps.some((dep, i) => dep !== old.deps[i])) {
      const next = { deps, cleanup: old?.cleanup };
      slots[index] = next;
      queue.push(() => { next.cleanup?.(); next.cleanup = run(); });
    }
  };
  const jsx = (type, props) => ({ type, props });
  const modules = {
    react: { useId: () => 'floating', useEffect: effect, useLayoutEffect: effect,
      useRef: (initial) => slots[cursor++] ??= { current: initial } },
    'react/jsx-runtime': { jsx, jsxs: jsx },
    'react-dom': { createPortal: (element) => element },
    'react-router-dom': { useLocation: () => ({ key: routeKey }) },
    './TopbarPopoverContext': { useTopbarPopover: () => ({ activeId, show: (id) => { activeId = id; }, closeAll }) },
    './floating-page-actions.css': {},
  };
  const document = { body: {}, fullscreenElement: null, querySelector: () => overlay ? {} : null,
    addEventListener: (name, fn) => listeners.set(name, fn),
    removeEventListener: (name, fn) => { if (listeners.get(name) === fn) listeners.delete(name); } };
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, document,
    MutationObserver: class {
      constructor(callback) { this.callback = callback; }
      observe() { observerCallback = this.callback; }
      disconnect() { observerCallback = null; }
    }, require: (id) => { assert.ok(id in modules, id); return modules[id]; } });
  const element = { contains: (node) => Boolean(node?.inside),
    querySelector: () => ({ focus: () => focus.push('action') }), focus: () => focus.push('trigger') };
  const render = () => {
    cursor = 0;
    const root = module.exports.FloatingPageActions(props);
    root.props.ref.current = element;
    root.props.children.filter(Boolean).forEach((child) => { if (child.props.ref) child.props.ref.current = element; });
    queue.splice(0).forEach((run) => run());
    return root;
  };
  return { render, focus, props, document,
    event: (name, event) => listeners.get(name)?.(event),
    topbar: () => { activeId = 'account'; },
    navigate: () => { routeKey = 'recent'; },
    modal: () => { overlay = true; observerCallback?.(); },
    cleanup: () => slots.forEach((slot) => slot?.cleanup?.()), listeners };
}
const trigger = (root) => root.props.children.at(-1);
const panel = (root) => root.props.children[0];

test('menu mode never becomes a direct action when selection changes; keyboard and mouse both work', () => {
  const app = setup({ mode: 'menu', label: 'File actions', selectedCount: 0, children: 'tools' });
  try {
    trigger(app.render()).props.onClick({ detail: 0 });
    assert.equal(panel(app.render()).props.hidden, false);
    assert.deepEqual(app.focus, ['action']);
    app.props.selectedCount = 1;
    assert.equal(trigger(app.render()).props['aria-haspopup'], 'dialog');
    app.event('keydown', { key: 'Escape' });
    assert.equal(panel(app.render()).props.hidden, true);
    assert.equal(app.focus.at(-1), 'trigger');
    trigger(app.render()).props.onClick({ detail: 1 });
    assert.equal(panel(app.render()).props.hidden, false);
    app.event('pointerdown', { target: { inside: true } });
    assert.equal(panel(app.render()).props.hidden, false);
    app.event('pointerdown', { target: {} });
    assert.equal(panel(app.render()).props.hidden, true);
  } finally { app.cleanup(); }
  assert.equal(app.listeners.size, 0);
});

test('topbar, route change and modal opening dismiss the panel without unmounting its tool children', () => {
  for (const action of ['topbar', 'navigate', 'modal']) {
    const app = setup({ mode: 'menu', label: 'Actions', children: 'persistent view options' });
    try {
      trigger(app.render()).props.onClick({ detail: 1 }); app.render();
      app[action]();
      const node = panel(app.render());
      assert.equal(node.props.hidden, true, action);
      assert.equal(node.props.children, 'persistent view options');
    } finally { app.cleanup(); }
  }
});

test('single mode invokes the existing action directly without rendering a menu', () => {
  let calls = 0;
  const app = setup({ mode: 'single', label: 'Empty trash', icon: 'fas fa-broom', onAction: () => { calls++; } });
  try {
    assert.equal(panel(app.render()), false);
    assert.equal(trigger(app.render()).props['aria-haspopup'], undefined);
    trigger(app.render()).props.onClick({ detail: 1 });
    assert.equal(calls, 1);
    app.props.disabled = true;
    assert.equal(trigger(app.render()).props.disabled, true);
  } finally { app.cleanup(); }
});

test('only the five approved pages opt in; exit search is removed and trash keeps confirmation', () => {
  for (const [path, mode] of [['files/BrowserToolbar', 'menu'], ['recent/RecentApp', 'menu'],
    ['bookmarks/BookmarksApp', 'menu'], ['shares/SharedLinksApp', 'single'], ['trash/TrashApp', 'single']]) {
    const source = readFileSync(new URL(`../src/${path}.tsx`, import.meta.url), 'utf8');
    assert.ok(source.includes(`<FloatingPageActions mode="${mode}"`));
    assert.doesNotMatch(source, /className="toolbar"|Exit search/);
  }
  const trash = readFileSync(new URL('../src/trash/TrashApp.tsx', import.meta.url), 'utf8');
  assert.match(trash, /askConfirmation\(/);
  assert.match(trash, /<section className="table-wrap" aria-label="Trash items">/);
  assert.doesNotMatch(trash, /Items \(\{items.length\}\)|className="section-heading"/);
  assert.match(trash, /onAction=\{\(\) => void empty\(\)\}/);
  for (const path of ['file-requests/FileRequestsApp', 'metadata/MetadataInspectorApp', 'vpn/VpnStatusApp']) {
    assert.doesNotMatch(readFileSync(new URL(`../src/${path}.tsx`, import.meta.url), 'utf8'), /FloatingPageActions/);
  }
});
