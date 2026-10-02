import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';
import { NOTE_MARGIN, projectNotePosition, pagePlacement, defaultNotePlacement, edgeScrollDelta } from '../../src/main/resources/static/js/sticky-note-placement.js';

const source = readFileSync(new URL('../../src/main/resources/static/js/sticky-notes.js', import.meta.url), 'utf8').replace(/^import[^\n]+\n/, '');
const copy = (value) => JSON.parse(JSON.stringify(value));
const settle = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
const note = (overrides = {}) => ({ id: 'note-1', content: 'Original', x: 500, y: 700, xRatio: 0.75,
  width: 280, height: 220, collapsed: false, layer: 1, revision: 0, ...overrides });

class Surface {
  listeners = new Map();
  addEventListener(type, callback) {
    const callbacks = this.listeners.get(type) || new Set();
    callbacks.add(callback); this.listeners.set(type, callbacks);
  }
  removeEventListener(type, callback) { this.listeners.get(type)?.delete(callback); }
  async emit(type, event = {}) {
    for (const callback of [...this.listeners.get(type) || []]) await callback(event);
    await settle();
  }
}

async function setup(notes = [note()], options = {}) {
  const window = new Surface();
  Object.assign(window, { innerWidth: 1500, innerHeight: 900, scrollY: 0, controlHeight: 38 });
  const frames = new Map();
  const timers = new Map();
  let sequence = 0;
  window.requestAnimationFrame = (callback) => { const id = ++sequence; frames.set(id, callback); return id; };
  window.cancelAnimationFrame = (id) => frames.delete(id);
  window.setTimeout = (callback, delay) => { const id = ++sequence; timers.set(id, { callback, delay }); return id; };
  window.clearTimeout = (id) => timers.delete(id);
  window.scrollBy = ({ top }) => { window.scrollY = Math.max(0, window.scrollY + top); };

  class Element extends Surface {
    children = [];
    attributes = new Map();
    dataset = {};
    classes = new Set();
    captured = new Set();
    style = { values: new Map(), setProperty(key, value) { this.values.set(key, value); }, getPropertyValue(key) { return this.values.get(key) || ''; } };
    constructor(tag = 'div') {
      super(); this.tag = tag;
      this.classList = { add: (name) => this.classes.add(name), remove: (name) => this.classes.delete(name),
        contains: (name) => this.classes.has(name), toggle: (name, on) => on ? this.classes.add(name) : this.classes.delete(name) };
    }
    set className(value) { this.classes = new Set(value.split(/\s+/)); }
    get className() { return [...this.classes].join(' '); }
    append(...children) { for (const child of children) { child.parentElement = this; this.children.push(child); } }
    remove() { this.parentElement.children = this.parentElement.children.filter((child) => child !== this); }
    setAttribute(name, value) { this.attributes.set(name, value); }
    matches(selector) {
      if (selector.startsWith('.')) return this.classes.has(selector.slice(1));
      if (selector.startsWith('[')) return this.attributes.has(selector.slice(1, -1));
      return this.tag === selector;
    }
    querySelector(selector) {
      for (const child of this.children) {
        if (child.matches(selector)) return child;
        const nested = child.querySelector(selector); if (nested) return nested;
      }
      return null;
    }
    closest(selector) { return this.matches(selector) ? this : this.parentElement?.closest(selector) || null; }
    focus() { document.activeElement = this; }
    setPointerCapture(id) { this.captured.add(id); }
    hasPointerCapture(id) { return this.captured.has(id); }
    releasePointerCapture(id) { this.captured.delete(id); }
    get offsetWidth() {
      if (this.classes.has('sticky-note-card')) return Math.min(parseFloat(this.style.width), Math.max(0, main.width - 16));
      return this.width || 0;
    }
    get offsetHeight() {
      if (this.classes.has('sticky-note-card')) return this.classes.has('is-collapsed') ? window.controlHeight + 4 : parseFloat(this.style.height);
      return this.height || 0;
    }
    get clientWidth() { return this.offsetWidth; }
    getBoundingClientRect() {
      let left = this.left || 0;
      let top = this.documentTop || 0;
      let width = this.offsetWidth;
      let height = this.offsetHeight;
      if (this === main) top -= window.scrollY;
      else if (this.classes.has('sticky-note-card')) {
        const parent = main.getBoundingClientRect();
        left = parent.left + parseFloat(this.style.left || 0); top = parent.top + parseFloat(this.style.top || 0);
      } else if (this.classes.has('sticky-note-header')) {
        const parent = this.parentElement.getBoundingClientRect();
        left = parent.left; top = parent.top; width = parent.width; height = window.controlHeight + 2;
      }
      return { left, top, width, height, right: left + width, bottom: top + height };
    }
  }

  const document = new Surface();
  document.readyState = 'complete'; document.visibilityState = 'visible';
  document.dispatchEvent = (event) => document.emit(event.type, event);
  document.body = new Element('body');
  document.createElement = (tag) => new Element(tag);
  let main, layer, controls, add, visibility;
  const makeShell = () => {
    main = new Element(); main.className = 'app-main'; main.width = 1200; main.left = 260; main.documentTop = 80;
    layer = new Element(); layer.className = 'sticky-note-layer'; layer.setAttribute('data-sticky-note-layer', ''); main.append(layer);
    controls = new Element(); controls.setAttribute('data-sticky-note-controls', '');
    add = new Element('button'); add.setAttribute('data-sticky-note-add', ''); controls.append(add);
    visibility = new Element('button'); visibility.setAttribute('data-sticky-note-visibility', ''); visibility.append(new Element('i')); controls.append(visibility);
  };
  makeShell();
  const topbar = new Element(); topbar.className = 'app-topbar'; topbar.height = 80;
  document.body.append(main, controls, topbar);
  document.querySelector = (selector) => {
    const meta = selector.match(/^meta\[name="endervault-sticky-(.+)"\]$/);
    if (meta) return { content: { 'target-type': 'PAGE', 'target-key': 'dashboard', surface: 'PAGE', label: 'Dashboard' }[meta[1]] };
    if (selector === 'dialog:modal') return document.modal ? {} : null;
    return document.body.querySelector(selector);
  };
  const observers = [];
  window.ResizeObserver = class {
    observed = new Set();
    constructor(callback) { this.callback = callback; observers.push(this); }
    observe(element) { this.observed.add(element); }
    unobserve(element) { this.observed.delete(element); }
    disconnect() { this.observed.clear(); }
  };
  const local = new Map();
  const localStorage = { getItem: (key) => local.get(key) || null, setItem: (key, value) => local.set(key, value), removeItem: (key) => local.delete(key) };
  const requests = [];
  const keepalives = [];
  const toasts = [];
  let globalAction;
  window.EnderVaultContextMenus = { registerGlobalAction: (action) => { globalAction = action; } };
  window.EnderVault = { csrfPair: () => ({ value: 'test-csrf' }), showToast: (...args) => toasts.push(args), showNotification() {},
    askConfirmation: async () => true, requestJson: async (url, init = {}) => {
      const request = { url, method: init.method || 'GET', body: init.body ? JSON.parse(init.body) : null, headers: init.headers };
      requests.push(request);
      if (request.method === 'GET') {
        const result = { notes: new URLSearchParams(url.split('?')[1]).get('targetKey') === 'dashboard' ? copy(notes) : [] };
        return options.read ? options.read(request, result) : result;
      }
      if (request.method === 'POST') return options.create ? options.create(request) : { note: note({ id: 'new-note', ...request.body }) };
      if (request.method === 'PUT') return options.save ? options.save(request) : { note: { ...request.body, revision: 1 } };
      return options.remove ? options.remove(request) : { deletedId: 'note-1' };
    } };
  const fetch = async (url, init) => { keepalives.push({ url, ...init, body: JSON.parse(init.body) }); };
  class CustomEvent {
    constructor(type, options = {}) { this.type = type; this.detail = options.detail; }
  }
  vm.runInNewContext(source, { document, window, localStorage, fetch, URLSearchParams, CustomEvent, NOTE_MARGIN,
    projectNotePosition, pagePlacement, defaultNotePlacement, edgeScrollDelta });
  await settle();
  const runFrames = async (time = 16) => {
    const callbacks = [...frames.values()]; frames.clear(); for (const callback of callbacks) callback(time); await settle();
  };
  const debounce = async () => {
    for (const [id, timer] of [...timers]) if (timer.delay === 1000) { timers.delete(id); timer.callback(); }
    await settle();
  };
  const pointer = (target, values) => ({ button: 0, pointerId: 1, target, preventDefault() {}, stopPropagation() {}, ...values });
  const unmount = async () => {
    await document.emit('endervault:spa-shell-disposed', { detail: { host: layer } });
    main.remove(); controls.remove();
  };
  const mount = async () => {
    makeShell(); document.body.append(main, controls);
    await document.emit('endervault:spa-shell-ready');
  };
  return { document, window, get main() { return main; }, get layer() { return layer; }, get controls() { return controls; },
    get add() { return add; }, get visibility() { return visibility; }, observers, requests, keepalives, toasts, frames, timers, local, unmount, mount,
    runFrames, debounce, pointer, globalAction: () => globalAction, cards: () => layer.children,
    writes: () => requests.filter((request) => request.method === 'PUT'),
    resize: async (width) => { main.width = width; observers.forEach((observer) => observer.callback()); await runFrames(); } };
}

test('scrolling and display resize never save or overwrite original page placement and dimensions', async () => {
  const h = await setup([note({ width: 600, height: 700, y: 15000 })]);
  assert.deepEqual(h.toasts, []);
  const card = h.cards()[0];
  const originalLeft = card.style.left;
  const originalTop = card.style.top;
  h.window.scrollY = 1000;
  assert.equal(card.getBoundingClientRect().top, 14080);
  await h.resize(360);
  assert.equal(card.offsetWidth, 344);
  assert.equal(card.style.left, '8px');
  assert.equal(card.style.top, originalTop);
  assert.equal(h.writes().length, 0);
  const editor = card.querySelector('textarea'); editor.value = 'Edited on narrow screen'; await editor.emit('input'); await h.debounce();
  assert.deepEqual(h.writes()[0].body, { content: 'Edited on narrow screen', x: 500, y: 15000, xRatio: 0.75,
    width: 600, height: 700, collapsed: false, layer: 1 });
  await h.resize(1200);
  assert.equal(card.style.left, originalLeft);
  assert.equal(card.style.top, originalTop);
});

test('legacy notes preserve old coordinates and dimensions through loading, resize and text-only autosave', async () => {
  const h = await setup([note({ x: 900, y: 480, xRatio: null, width: 600, height: 700 })]);
  const card = h.cards()[0];
  assert.equal(card.style.top, '400px');
  await h.resize(360);
  const editor = card.querySelector('textarea'); editor.value = 'Legacy edit'; await editor.emit('input'); await h.debounce();
  const body = h.writes()[0].body;
  assert.equal(body.x, 900); assert.equal(body.y, 480); assert.equal(body.xRatio, null);
  assert.equal(body.width, 600); assert.equal(body.height, 700);
  assert.equal(h.writes()[0].headers['X-CSRF-TOKEN'], 'test-csrf');
});

test('creating below the fold stores page coordinates and does not jump scroll while focusing', async () => {
  const h = await setup([]); h.window.scrollY = 1000;
  await h.globalAction().run({ event: { clientX: 600, clientY: 300 } });
  const created = h.requests.find((request) => request.method === 'POST');
  assert.equal(created.body.y, 1220);
  assert.equal(created.body.xRatio, 332 / 904);
  assert.equal(h.cards()[0].getBoundingClientRect().top, 300);
  assert.equal(h.window.scrollY, 1000);
  assert.equal(h.document.activeElement, h.cards()[0].querySelector('textarea'));
});

test('dragging translates scroll offsets and explicitly migrates legacy coordinates', async () => {
  const h = await setup([note({ x: 100, y: 480, xRatio: null })]); h.window.scrollY = 200;
  const card = h.cards()[0]; const header = card.querySelector('header'); const rect = card.getBoundingClientRect();
  await header.emit('pointerdown', h.pointer(header, { clientX: rect.left + 10, clientY: rect.top + 10 }));
  await header.emit('pointermove', h.pointer(header, { clientX: rect.left + 110, clientY: rect.top + 90 }));
  await header.emit('pointerup', h.pointer(header, {})); await h.debounce();
  const body = h.writes()[0].body;
  assert.equal(body.x, 200); assert.equal(body.y, 480); assert.equal(body.xRatio, 192 / 904);
  assert.equal(header.captured.size, 0); assert.equal(h.frames.size, 0);
  assert.equal(h.layer.classList.contains('is-interacting'), false);
});

test('edge scrolling follows the captured pointer and cancellation stops all pointer work', async () => {
  const h = await setup(); const header = h.cards()[0].querySelector('header'); const rect = header.getBoundingClientRect();
  await header.emit('pointerdown', h.pointer(header, { clientX: rect.left + 10, clientY: rect.top + 10 }));
  await header.emit('pointermove', h.pointer(header, { clientX: rect.left + 20, clientY: 890 }));
  await h.runFrames(); assert.ok(h.window.scrollY > 0);
  await header.emit('pointercancel', h.pointer(header, {}));
  assert.equal(h.frames.size, 0); assert.equal(header.captured.size, 0);
  assert.equal(header.listeners.get('pointermove').size, 0);
  assert.equal(h.window.listeners.get('blur').size, 0);
  await h.debounce(); assert.equal(h.writes().length, 1);
});

test('resizing a legacy note below the fold migrates placement while retaining its displayed origin', async () => {
  const h = await setup([note({ x: 100, y: 480, xRatio: null })]); h.window.scrollY = 200;
  const card = h.cards()[0]; const handle = card.querySelector('.sticky-note-resize-handle');
  const rect = card.getBoundingClientRect();
  await handle.emit('pointerdown', h.pointer(handle, { clientX: rect.right, clientY: rect.bottom }));
  await handle.emit('pointermove', h.pointer(handle, { clientX: rect.right + 100, clientY: rect.bottom + 80 }));
  await handle.emit('pointerup', h.pointer(handle, {})); await h.debounce();
  const body = h.writes()[0].body;
  assert.equal(body.width, 380); assert.equal(body.height, 300);
  assert.equal(body.x, 100); assert.equal(body.y, 400); assert.equal(body.xRatio, 92 / 804);
  assert.equal(card.getBoundingClientRect().left, rect.left);
  assert.equal(card.getBoundingClientRect().top, rect.top);
  assert.equal(h.layer.classList.contains('is-interacting'), false);
});

test('hiding the tab ends the gesture before saving the final page coordinates', async () => {
  const h = await setup(); const header = h.cards()[0].querySelector('header'); const rect = header.getBoundingClientRect();
  await header.emit('pointerdown', h.pointer(header, { clientX: rect.left + 10, clientY: rect.top + 10 }));
  await header.emit('pointermove', h.pointer(header, { clientX: rect.left + 50, clientY: rect.top + 30 }));
  h.document.visibilityState = 'hidden'; await h.document.emit('visibilitychange');
  assert.equal(h.keepalives.length, 1); assert.equal(h.keepalives[0].body.y, 720);
  assert.equal(header.captured.size, 0); assert.equal(h.frames.size, 0);
  assert.equal(h.layer.classList.contains('is-interacting'), false);
});

test('other pointer releases do not stop a drag, but opening a modal ends its edge scrolling', async () => {
  const h = await setup(); const header = h.cards()[0].querySelector('header'); const rect = header.getBoundingClientRect();
  await header.emit('pointerdown', h.pointer(header, { clientX: rect.left + 10, clientY: rect.top + 10 }));
  await header.emit('pointermove', h.pointer(header, { clientX: rect.left + 20, clientY: 890 }));
  await header.emit('pointerup', h.pointer(header, { pointerId: 2 }));
  assert.equal(header.captured.size, 1);
  h.document.modal = true; await h.runFrames();
  assert.equal(header.captured.size, 0); assert.equal(h.frames.size, 0);
  assert.equal(h.window.scrollY, 0); await h.debounce(); assert.equal(h.writes().length, 1);
});

test('hiding, deleting and leaving the context release note-only page height', async () => {
  const h = await setup([note({ y: 15000 })]);
  assert.equal(h.main.style.getPropertyValue('--sticky-note-page-height'), '15228px');
  await h.visibility.emit('click'); assert.equal(h.main.style.getPropertyValue('--sticky-note-page-height'), '0px');
  await h.visibility.emit('click'); assert.equal(h.main.style.getPropertyValue('--sticky-note-page-height'), '15228px');
  await h.window.EnderVaultStickyNotes.setContext({ targetType: 'PAGE', targetKey: 'other', surface: 'PAGE', label: 'Other' });
  assert.equal(h.main.style.getPropertyValue('--sticky-note-page-height'), '0px');
  assert.equal(h.cards().length, 0);
  await h.window.EnderVaultStickyNotes.setContext({ targetType: 'PAGE', targetKey: 'dashboard', surface: 'PAGE', label: 'Dashboard' });
  await h.document.emit('endervault:sticky-note-deleted', { detail: { id: 'note-1' } });
  assert.equal(h.main.style.getPropertyValue('--sticky-note-page-height'), '0px');
});

test('collapse and changing UI size keep the original expanded dimensions', async () => {
  const h = await setup([note({ height: 700 })]); const card = h.cards()[0];
  await card.querySelector('header').emit('dblclick', h.pointer(card.querySelector('header'), {}));
  h.window.controlHeight = 46; await h.resize(360); await h.debounce();
  assert.equal(card.offsetHeight, 50); assert.equal(h.writes()[0].body.height, 700);
  assert.equal(h.writes()[0].body.width, 280);
  await card.querySelector('header').emit('dblclick', h.pointer(card.querySelector('header'), {}));
  assert.equal(card.offsetHeight, 700);
});

test('context switches during a drag persist once, remove capture and cancel old autosave timers', async () => {
  const h = await setup(); const header = h.cards()[0].querySelector('header'); const rect = header.getBoundingClientRect();
  await header.emit('pointerdown', h.pointer(header, { clientX: rect.left + 10, clientY: rect.top + 10 }));
  await header.emit('pointermove', h.pointer(header, { clientX: rect.left + 50, clientY: rect.top + 30 }));
  await h.window.EnderVaultStickyNotes.setContext({ targetType: 'PAGE', targetKey: 'other', surface: 'PAGE', label: 'Other' });
  assert.equal(h.keepalives.length, 1); assert.equal(h.keepalives[0].body.y, 720);
  assert.equal(h.keepalives[0].keepalive, true); assert.equal(header.captured.size, 0);
  assert.equal(h.frames.size, 0); assert.equal(h.timers.size, 0);
  assert.equal(h.observers[0].observed.size, 1);
  await h.debounce(); assert.equal(h.writes().length, 0);
});

test('a late creation response cannot place an old-context note on the next SPA page', async () => {
  let resolve;
  const h = await setup([], { create: () => new Promise((done) => { resolve = done; }) });
  const creation = h.globalAction().run({ event: { clientX: 300, clientY: 400 } }); await settle();
  await h.window.EnderVaultStickyNotes.setContext({ targetType: 'PAGE', targetKey: 'other', surface: 'PAGE', label: 'Other' });
  resolve({ note: note({ id: 'late' }) }); await creation;
  assert.equal(h.cards().length, 0); assert.equal(h.document.activeElement, undefined);
});

test('repeated readiness keeps one host binding and one set of global lifecycle listeners', async () => {
  const h = await setup();
  for (let i = 0; i < 3; i++) await h.document.emit('endervault:spa-shell-ready');
  await h.document.emit('endervault:spa-shell-disposed');
  assert.equal(h.cards().length, 1);
  assert.equal(h.requests.filter((request) => request.method === 'GET').length, 1);
  assert.equal(h.observers.length, 1); assert.equal(h.add.listeners.get('click').size, 1);
  await h.add.emit('click'); assert.equal(h.requests.filter((request) => request.method === 'POST').length, 1);
  assert.equal(h.window.listeners.get('resize').size, 1); assert.equal(h.window.listeners.get('pagehide').size, 1);
  assert.equal(h.document.listeners.get('visibilitychange').size, 1);
});

test('same-host detach and readiness reconnect safely without accumulating controls or observers', async () => {
  const h = await setup(); const host = h.layer;
  await h.visibility.emit('click');
  await h.document.emit('endervault:spa-shell-disposed', { detail: { host } });
  assert.equal(h.cards().length, 0); assert.equal(h.observers[0].observed.size, 0);
  assert.equal(h.add.listeners.get('click').size, 0); assert.equal(h.globalAction().visible(), false);
  await h.document.emit('endervault:spa-shell-ready');
  assert.equal(h.cards().length, 1); assert.equal(h.layer.hidden, true);
  assert.equal(h.observers[1].observed.size, 2); assert.equal(h.add.listeners.get('click').size, 1);
  assert.equal(h.globalAction().visible(), true);
  await h.visibility.emit('click'); assert.equal(h.layer.hidden, false);
  assert.equal(h.main.style.getPropertyValue('--sticky-note-page-height'), '928px');
  assert.equal(h.window.listeners.get('resize').size, 1);
});

test('shell removal ends a drag, retains unsaved content, and reconnects new controls to the new host', async () => {
  const h = await setup(); const old = { main: h.main, layer: h.layer, add: h.add };
  const card = h.cards()[0]; const editor = card.querySelector('textarea'); editor.value = 'Retained edit'; await editor.emit('input');
  const header = card.querySelector('header'); const rect = header.getBoundingClientRect();
  await header.emit('pointerdown', h.pointer(header, { clientX: rect.left + 10, clientY: rect.top + 10 }));
  await header.emit('pointermove', h.pointer(header, { clientX: rect.left + 30, clientY: 890 }));
  await h.window.emit('resize'); assert.ok(h.frames.size > 0);
  await h.unmount();
  assert.equal(header.captured.size, 0); assert.equal(h.frames.size, 0); assert.equal(h.timers.size, 0);
  assert.equal(h.observers[0].observed.size, 0); assert.equal(old.layer.classList.contains('is-interacting'), false);
  assert.equal(old.main.style.getPropertyValue('--sticky-note-page-height'), '0px');
  assert.equal(h.keepalives.length, 1); assert.equal(h.keepalives[0].body.content, 'Retained edit');
  assert.equal(h.globalAction().visible(), false);
  await h.mount();
  assert.notEqual(h.layer, old.layer); assert.equal(h.cards().length, 1);
  assert.equal(h.cards()[0].querySelector('textarea').value, 'Retained edit');
  await h.document.emit('endervault:spa-shell-disposed', { detail: { host: old.layer } });
  assert.equal(h.cards().length, 1);
  await old.add.emit('click'); assert.equal(h.requests.filter((request) => request.method === 'POST').length, 0);
  await h.add.emit('click'); assert.equal(h.requests.filter((request) => request.method === 'POST').length, 1);
  assert.equal(old.layer.children.length, 0); assert.equal(h.cards().length, 2);
});

for (const outcome of ['resolve', 'reject']) {
  test(`an old shell's delayed read ${outcome} cannot replace new notes or show a stale error`, async () => {
    let finish; let reads = 0;
    const h = await setup([], { read: () => ++reads === 1 ? new Promise((resolve, reject) => {
      finish = outcome === 'resolve' ? () => resolve({ notes: [note({ id: 'old-response' })] })
        : () => reject(new Error('Old shell failed'));
    }) : { notes: [note({ id: 'current-response' })] } });
    await h.unmount(); await h.mount();
    finish(); await settle();
    assert.deepEqual(h.cards().map((card) => card.dataset.stickyNoteId), ['current-response']);
    assert.equal(h.toasts.length, 0); assert.equal(h.observers[0].observed.size, 0);
    assert.equal(h.window.listeners.get('resize').size, 1);
  });
}

test('shell disposal invalidates a creation response even when reattached to the same page context', async () => {
  let resolve;
  const h = await setup([], { create: () => new Promise((done) => { resolve = done; }) });
  const creation = h.globalAction().run({ event: { clientX: 300, clientY: 400 } }); await settle();
  await h.unmount(); await h.mount();
  resolve({ note: note({ id: 'late-shell-note' }) }); await creation;
  assert.equal(h.cards().length, 0); assert.equal(h.document.activeElement, undefined);
});

test('a detached save response cannot clear the newer host edit backup or restart its old queue', async () => {
  let resolve; let saves = 0;
  const h = await setup([note()], { save: (request) => ++saves === 1 ? new Promise((done) => { resolve = done; })
    : { note: { ...request.body, revision: 2 } } });
  let editor = h.cards()[0].querySelector('textarea'); editor.value = 'First save'; await editor.emit('input'); await h.debounce();
  await h.unmount(); await h.mount();
  editor = h.cards()[0].querySelector('textarea'); assert.equal(editor.value, 'First save');
  editor.value = 'Newer edit'; await editor.emit('input');
  resolve({ note: { revision: 1 } }); await settle();
  assert.equal(JSON.parse(h.local.get('endervault.stickyNote.unsaved:note-1')).content, 'Newer edit');
  assert.equal(h.writes().length, 1); assert.equal(h.cards()[0].querySelector('.sticky-note-status').textContent, 'Unsaved');
  await h.debounce(); assert.equal(h.writes().length, 2); assert.equal(h.writes()[1].body.content, 'Newer edit');
  assert.equal(h.local.has('endervault.stickyNote.unsaved:note-1'), false);
});

test('remount clears a retained backup once the canonical read confirms the same saved content', async () => {
  let resolve; let canonical = note();
  const h = await setup([], { read: () => ({ notes: [canonical] }), save: (request) => new Promise((done) => {
    resolve = () => { canonical = note(request.body); done({ note: canonical }); };
  }) });
  const editor = h.cards()[0].querySelector('textarea'); editor.value = 'Confirmed edit'; await editor.emit('input'); await h.debounce();
  await h.unmount(); resolve(); await settle();
  assert.equal(h.local.has('endervault.stickyNote.unsaved:note-1'), true);
  await h.mount();
  assert.equal(h.cards()[0].querySelector('textarea').value, 'Confirmed edit');
  assert.equal(h.local.has('endervault.stickyNote.unsaved:note-1'), false);
  assert.equal(h.timers.size, 0); assert.equal(h.writes().length, 1);
});

test('a confirmed deletion that completes after remount removes the current card through the shared event', async () => {
  let resolve;
  const h = await setup([note()], { remove: () => new Promise((done) => { resolve = done; }) });
  await h.cards()[0].querySelector('.sticky-note-actions').children[1].emit('click');
  assert.equal(h.requests.filter((request) => request.method === 'DELETE').length, 1);
  await h.unmount(); await h.mount(); assert.equal(h.cards().length, 1);
  resolve({ deletedId: 'note-1' }); await settle();
  assert.equal(h.cards().length, 0); assert.equal(h.main.style.getPropertyValue('--sticky-note-page-height'), '0px');
  assert.equal(h.toasts.some(([type]) => type === 'error'), false);
});

test('topbar creation stays visible at the twentieth note without scrolling or rewriting existing positions', async () => {
  const h = await setup(Array.from({ length: 19 }, (_, index) => note({ id: `note-${index}` })));
  const existing = h.cards().map((card) => ({ left: card.style.left, top: card.style.top }));
  h.window.innerHeight = 400; await h.add.emit('click');
  const rect = h.cards().at(-1).getBoundingClientRect();
  assert.equal(rect.top, 172); assert.equal(rect.bottom, 392); assert.equal(h.window.scrollY, 0);
  assert.deepEqual(h.cards().slice(0, 19).map((card) => ({ left: card.style.left, top: card.style.top })), existing);
  assert.equal(h.writes().length, 0);
});

test('topbar creation in a short scrolled viewport keeps the header visible while context creation retains its point', async () => {
  const h = await setup([]); h.window.scrollY = 1000; h.window.innerHeight = 180;
  await h.resize(360); await h.add.emit('click');
  assert.equal(h.cards()[0].getBoundingClientRect().top, 88);
  assert.ok(h.cards()[0].querySelector('header').getBoundingClientRect().bottom < h.window.innerHeight);
  assert.equal(h.requests.find((request) => request.method === 'POST').body.y, 1008);
  await h.globalAction().run({ event: { clientX: 300, clientY: 170 } });
  assert.equal(h.cards().at(-1).getBoundingClientRect().top, 170);
  assert.equal(h.window.scrollY, 1000); assert.equal(h.writes().length, 0);
});
