import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

const source = readFileSync(new URL('../../src/main/resources/static/js/context-menu.js', import.meta.url), 'utf8');

class Surface {
  listeners = new Map();
  addEventListener(type, listener, options) {
    const listeners = this.listeners.get(type) || [];
    listeners.push({ listener, options });
    this.listeners.set(type, listeners);
  }
  removeEventListener(type, listener, options) {
    this.listeners.set(type, (this.listeners.get(type) || [])
      .filter((entry) => entry.listener !== listener || entry.options !== options));
  }
  async emit(type, event = {}) {
    for (const { listener } of [...this.listeners.get(type) || []]) await listener(event);
  }
  count() { return [...this.listeners.values()].reduce((count, listeners) => count + listeners.length, 0); }
}

class Element extends Surface {
  children = [];
  classList = { values: new Set(), add(value) { this.values.add(value); }, remove(value) { this.values.delete(value); } };
  dataset = {};
  style = {};
  append(...children) { children.forEach((child) => { child.parent = this; this.children.push(child); }); }
  remove() { this.parent.children = this.parent.children.filter((child) => child !== this); }
  contains(target) { return target === this || this.children.some((child) => child.contains(target)); }
  setAttribute() {}
  getBoundingClientRect() { return { width: 240, height: 120 }; }
}

function setup() {
  const document = new Surface();
  document.body = new Element();
  document.createElement = () => new Element();
  const window = new Surface();
  window.innerWidth = 1000;
  window.innerHeight = 800;
  let activeCloser;
  window.EnderVault = {
    showToast() {},
    contextMenus: {
      open(close) { if (activeCloser !== close) activeCloser?.(); activeCloser = close; },
      clear(close) { if (activeCloser === close) activeCloser = null; },
    },
  };
  vm.runInNewContext(source, { document, window });
  return { document, window, menus: window.EnderVaultContextMenus };
}

test('page owners and every listener are released across SPA visits and StrictMode remounts', async () => {
  const { menus, document, window } = setup();
  let calls = 0;
  for (const owner of ['files', 'bookmarks', 'recent', 'files', 'files', 'bookmarks']) {
    const menu = menus.createActionMenu({ menuId: owner, pageScope: owner, actions: [],
      contextForEvent: () => { calls++; return null; }, extraCloseEvents: ['refreshed'] });
    assert.ok(menu);
    assert.equal(menus.pageScopeOwner(), owner);
    assert.equal(document.count(), 4);
    assert.equal(window.count(), 2);
    assert.equal(menus.createActionMenu({ pageScope: owner, actions: [], contextForEvent() {} }), null);
    const before = calls;
    await document.emit('contextmenu');
    assert.equal(calls, before + 1);
    menu.dispose();
    menu.dispose();
    assert.equal(document.count(), 0);
    assert.equal(window.count(), 0);
    assert.equal(menus.pageScopeOwner(), '');
    await document.emit('contextmenu');
    assert.equal(calls, before + 1);
  }
});

test('close preserves registration; dispose removes target highlight and cannot release the next owner', async () => {
  const { menus, document } = setup();
  const target = new Element();
  let ran = 0;
  let actions = [{ id: 'old', label: 'Old', icon: '', run: () => { ran++; } }];
  const menu = menus.createActionMenu({ menuId: 'file', pageScope: 'files', actions: () => actions,
    contextForEvent: () => ({ item: { element: target } }) });
  const event = { target, preventDefault() {}, clientX: 20, clientY: 30 };
  await document.emit('contextmenu', event);
  assert.equal(target.classList.values.has('is-context-target'), true);
  menu.close();
  assert.equal(document.body.children.length, 0);
  assert.equal(menus.pageScopeOwner(), 'files');
  actions = [{ id: 'current', label: 'Current', icon: '', run: () => { ran += 10; } }];
  await document.emit('contextmenu', event);
  const button = document.body.children[0].children[0];
  assert.equal(button.dataset.contextAction, 'current');
  await button.emit('click');
  assert.equal(ran, 10);
  await document.emit('contextmenu', event);
  const staleButton = document.body.children[0].children[0];
  menu.dispose();
  assert.equal(target.classList.values.has('is-context-target'), false);
  assert.equal(document.body.classList.values.has('context-menu-open'), false);
  const next = menus.createActionMenu({ pageScope: 'recent', actions: [], contextForEvent: () => null });
  menu.dispose();
  assert.equal(menus.pageScopeOwner(), 'recent');
  await staleButton.emit('click');
  assert.equal(ran, 10);
  next.dispose();
});

test('closing an inactive page fallback cannot erase another open menu state', async () => {
  const { menus, document } = setup();
  const target = new Element();
  const action = { id: 'open', label: 'Open', icon: '', run() {} };
  const menu = menus.createActionMenu({ pageScope: 'files', actions: [action],
    contextForEvent: () => ({ item: { element: target } }) });
  menus.createActionMenu({ actions: [], contextForEvent: () => null });
  await document.emit('contextmenu', { target, preventDefault() {}, clientX: 0, clientY: 0 });
  assert.equal(document.body.classList.values.has('context-menu-open'), true);
  assert.equal(document.body.children.length, 1);
  menu.dispose();
});

test('unclaimed pages regain global sticky note actions after a scoped page unmounts', async () => {
  const { menus, document } = setup();
  menus.registerGlobalAction({ id: 'sticky', group: 'global', label: 'New note', icon: '', run() {} });
  menus.createActionMenu({ menuId: 'fallback', actions: [],
    contextForEvent: () => menus.pageScopeOwner() ? null : { mode: 'page-background' } });
  const page = menus.createActionMenu({ pageScope: 'files', actions: [], contextForEvent: () => null });
  const event = { target: new Element(), preventDefault() {}, clientX: 0, clientY: 0 };
  await document.emit('contextmenu', event);
  assert.equal(document.body.children.length, 0);
  page.dispose();
  await document.emit('contextmenu', event);
  assert.equal(document.body.children.length, 1);
  assert.equal(document.body.children[0].children[0].dataset.contextAction, 'sticky');
});
