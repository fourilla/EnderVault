import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';

const result = await build({ configFile: false, logLevel: 'silent', build: {
  write: false, minify: false,
  lib: { entry: fileURLToPath(new URL('../src/shared/browser/useItemSelection.ts', import.meta.url)), formats: ['cjs'] },
  rolldownOptions: { external: ['react'] },
} });
const code = (Array.isArray(result) ? result[0] : result).output.find((item) => item.type === 'chunk').code;

function setup(itemKey) {
  let index = 0;
  const slots = [], effects = [], listeners = new Map(), timers = new Map();
  let timerId = 0;
  const window = {
    setTimeout: (fn) => { timers.set(++timerId, fn); return timerId; },
    clearTimeout: (id) => timers.delete(id),
  };
  const document = {
    addEventListener: (name, listener) => listeners.set(name, listener),
    removeEventListener: (name, listener) => { if (listeners.get(name) === listener) listeners.delete(name); },
  };
  const react = {
    useState(initial) {
      const i = index++;
      if (!(i in slots)) slots[i] = typeof initial === 'function' ? initial() : initial;
      return [slots[i], (value) => { slots[i] = typeof value === 'function' ? value(slots[i]) : value; }];
    },
    useRef(initial) { const i = index++; return slots[i] ??= { current: initial }; },
    useMemo: (fn) => fn(),
    useEffect(fn, deps) {
      const i = index++;
      if (!slots[i] || deps.some((dep, j) => dep !== slots[i].deps[j])) {
        slots[i]?.cleanup?.();
        const effect = { deps, cleanup: null };
        slots[i] = effect;
        effects.push(() => { effect.cleanup = fn(); });
      }
    },
  };
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, document, window, require: () => react });
  const items = [{ path: 'a.txt', id: 'a' }, { path: 'b.txt', id: 'b' }, { path: 'c.txt', id: 'c' }];
  const props = { items, enabled: true, locationKey: 'page', itemKey, openItem() {} };
  const render = () => {
    index = 0;
    const state = module.exports.useItemSelection(props);
    effects.splice(0).forEach((effect) => effect());
    return state;
  };
  render();
  const click = (ancestors) => listeners.get('click')({ target: {
    closest: (selector) => selector.split(',').some((part) => ancestors.includes(part.trim())) ? {} : null,
  } });
  return { items, render, click, props, timers, listeners,
    cleanup: () => slots.forEach((slot) => slot?.cleanup?.()) };
}

function interaction(s, item, modifiers = {}, checkbox = '') {
  const event = { button: 0, ctrlKey: false, metaKey: false, shiftKey: false, altKey: false, ...modifiers,
    currentTarget: { querySelector: () => null },
    target: { closest: (selector) => checkbox && selector.split(',').some((part) =>
      ['input', `input[type="checkbox"].${checkbox}`].includes(part.trim())) ? {} : null },
    prevented: false, stopped: false,
    preventDefault() { this.prevented = true; }, stopPropagation() { this.stopped = true; } };
  const handlers = s.render().itemInteractionProps(item);
  if (checkbox) {
    event.nativeEvent = event;
    handlers.onChangeCapture(event);
    if (!event.stopped) s.render().selectItem(item, !s.render().selected.has(s.props.itemKey(item)));
  } else {
    handlers.onMouseDownCapture(event);
    handlers.onClickCapture(event);
  }
  s.render();
  return event;
}

for (const [name, key] of [['Files', (item) => item.path], ['Bookmarks', (item) => item.id]]) {
  test(`${name}: select-all input and label clicks survive document bubbling, including partial selection`, () => {
    const s = setup(key);
    try {
      for (const target of ['.select-all-checkbox', '.select-all-label']) {
        let selection = s.render();
        selection.selectItem(s.items[0], true);
        selection = s.render();
        s.items.forEach((item) => selection.selectItem(item, true));
        s.render(); // React flushes the checkbox update before the native document listener.
        s.click([target]);
        assert.equal(s.render().selected.size, 3);
        selection = s.render();
        s.items.forEach((item) => selection.selectItem(item, false));
        s.render(); s.click([target]);
        assert.equal(s.render().selected.size, 0);
      }
    } finally { s.cleanup(); }
  });
}

test('row, toolbar and dialog interactions preserve selection; actual background and navigation clear it', () => {
  const s = setup((item) => item.path);
  try {
    s.render().selectItem(s.items[0], true);
    s.render();
    for (const target of ['[data-context-item="true"]', '.toolbar', '.floating-page-actions', '.toast-region', 'dialog']) {
      s.click([target]); assert.equal(s.render().selected.size, 1);
    }
    s.click([]); assert.equal(s.render().selected.size, 0);
    s.render().selectItem(s.items[1], true);
    s.props.locationKey = 'next-page'; s.render();
    assert.equal(s.render().selected.size, 0);
  } finally { s.cleanup(); }
});

test('selectAll selects exactly the supplied page and is idempotent, never a toggle', () => {
  const s = setup((item) => item.path);
  try {
    s.props.items = [s.items[0], { path: 'visible-hidden.txt', hidden: true }];
    s.render().selectAll();
    const selected = s.render().selected;
    assert.deepEqual([...selected], ['a.txt', 'visible-hidden.txt']);
    s.render().selectAll();
    assert.equal(s.render().selected, selected);
    s.render().clearSelection();
    assert.equal(s.render().selected.size, 0);
    const cleared = s.render().selected;
    s.render().clearSelection();
    assert.equal(s.render().selected, cleared);
    s.props.enabled = false;
    s.render().selectAll();
    assert.equal(s.render().selected.size, 0);
  } finally { s.cleanup(); }
});

test('refresh prunes missing identities without selecting newly added items or using their names', () => {
  const s = setup((item) => item.path);
  try {
    s.render().selectAll(); s.render();
    s.props.items = [s.items[2], s.items[0]];
    s.render();
    assert.deepEqual([...s.render().selected], ['a.txt', 'c.txt']);
    s.props.items = [{ path: 'd.txt', name: 'a.txt' }, s.items[2]];
    s.render();
    assert.deepEqual([...s.render().selected], ['c.txt']);
    s.props.items = [];
    s.render();
    assert.equal(s.render().selected.size, 0);
    s.render().selectAll();
    assert.equal(s.render().selected.size, 0);
  } finally { s.cleanup(); }
});

test('a same-list view change preserves selection while a changed condition key clears it', () => {
  const s = setup((item) => item.path);
  try {
    s.render().selectItem(s.items[0], true);
    s.props.items = s.items.map((item) => ({ ...item }));
    s.render();
    assert.deepEqual([...s.render().selected], ['a.txt']);
    s.props.locationKey = 'different-sort-or-visibility';
    s.render();
    assert.equal(s.render().selected.size, 0);
  } finally { s.cleanup(); }
});

test('selection-only pages never register keyboard listeners', () => {
  const s = setup((item) => item.id);
  try {
    assert.equal(s.listeners.has('keydown'), false);
  } finally { s.cleanup(); }
  assert.equal(s.listeners.size, 0);
});

for (const change of ['location', 'disabled', 'unmount']) {
  test(`pending long presses are canceled on ${change}, before they can select an old item`, () => {
    const s = setup((item) => item.path);
    s.render().itemInteractionProps(s.items[0]).onPointerDown({ button: 0, pointerId: 1,
      clientX: 0, clientY: 0, target: { closest: () => null } });
    assert.equal(s.timers.size, 1);
    if (change === 'location') s.props.locationKey = 'next';
    if (change === 'disabled') s.props.enabled = false;
    if (change === 'unmount') s.cleanup();
    else { s.render(); s.cleanup(); }
    assert.equal(s.timers.size, 0);
    assert.equal(s.listeners.size, 0);
  });
}

for (const checkbox of ['', 'row-select-checkbox', 'card-check']) {
  test(`${checkbox || 'row/card'}: Shift replaces selection with a fixed-anchor range and shrinks on repetition`, () => {
    const s = setup((item) => item.path);
    try {
      s.props.items = [...s.items, { path: 'd.txt' }, { path: 'e.txt' }];
      s.render();
      const first = interaction(s, s.props.items[0], { ctrlKey: true }, checkbox);
      assert.deepEqual([...s.render().selected], ['a.txt']);
      interaction(s, s.props.items[3], { shiftKey: true }, checkbox);
      assert.deepEqual([...s.render().selected], ['a.txt', 'b.txt', 'c.txt', 'd.txt']);
      interaction(s, s.props.items[1], { shiftKey: true }, checkbox);
      assert.deepEqual([...s.render().selected], ['a.txt', 'b.txt']);
      interaction(s, s.props.items[4], { shiftKey: true }, checkbox);
      assert.equal(s.render().selected.size, 5);
      interaction(s, s.props.items[2], { ctrlKey: true }, checkbox);
      interaction(s, s.props.items[0], { shiftKey: true }, checkbox);
      assert.deepEqual([...s.render().selected], ['a.txt', 'b.txt', 'c.txt']);
      if (!checkbox) assert.equal(first.prevented, true);
    } finally { s.cleanup(); }
  });
}

test('Shift without an anchor selects only the endpoint, never opens it or toggles it off', () => {
  const s = setup((item) => item.path), opened = [];
  s.props.openItem = (item) => opened.push(item.path);
  try {
    const event = interaction(s, s.items[1], { shiftKey: true });
    assert.equal(event.prevented, true);
    assert.equal(event.stopped, true);
    assert.deepEqual([...s.render().selected], ['b.txt']);
    interaction(s, s.items[1], { shiftKey: true });
    assert.deepEqual([...s.render().selected], ['b.txt']);
    assert.deepEqual(opened, []);
  } finally { s.cleanup(); }
});

test('Shift row mousedown focuses its selection control without scrolling; action controls keep their focus', () => {
  const s = setup((item) => item.path), focused = [];
  try {
    const handlers = s.render().itemInteractionProps(s.items[0]);
    const event = { button: 0, shiftKey: true, target: { closest: () => null },
      currentTarget: { querySelector: () => ({ focus: (options) => focused.push(options) }) },
      preventDefault() {} };
    handlers.onMouseDownCapture(event);
    assert.equal(focused.length, 1);
    assert.equal(focused[0].preventScroll, true);
    handlers.onMouseDownCapture({ ...event, target: { closest: () => ({}) } });
    handlers.onMouseDownCapture({ ...event, button: 2 });
    assert.equal(focused.length, 1);
  } finally { s.cleanup(); }
});

test('range follows supplied display order across directory/file sections and visible hidden entries', () => {
  const s = setup((item) => item.path);
  try {
    s.props.items = [{ path: 'z-dir', type: 'directory' }, { path: 'a-dir', type: 'directory' },
      { path: 'z.txt' }, { path: '.hidden.txt', hidden: true }, { path: 'a.txt' }];
    s.render();
    interaction(s, s.props.items[1], { ctrlKey: true });
    interaction(s, s.props.items[4], { shiftKey: true });
    assert.deepEqual([...s.render().selected], ['a-dir', 'z.txt', '.hidden.txt', 'a.txt']);
  } finally { s.cleanup(); }
});

for (const reset of ['clear', 'background', 'condition', 'missing-anchor']) {
  test(`${reset}: clears the anchor; the next Shift starts with only its endpoint`, () => {
    const s = setup((item) => item.path);
    try {
      interaction(s, s.items[0], { ctrlKey: true });
      if (reset === 'clear') s.render().clearSelection();
      if (reset === 'background') s.click([]);
      if (reset === 'condition') s.props.locationKey = 'next';
      if (reset === 'missing-anchor') s.props.items = s.items.slice(1);
      s.render();
      interaction(s, s.items[2], { shiftKey: true });
      assert.deepEqual([...s.render().selected], ['c.txt']);
    } finally { s.cleanup(); }
  });
}

test('view changes retain stable anchor identity; header/programmatic selection does not change the anchor', () => {
  const s = setup((item) => item.id);
  try {
    interaction(s, s.items[1], {}, 'row-select-checkbox');
    s.render().selectAll();
    s.props.items = s.items.map((item) => ({ ...item })); s.render();
    interaction(s, s.props.items[2], { shiftKey: true }, 'card-check');
    assert.deepEqual([...s.render().selected], ['b', 'c']);
  } finally { s.cleanup(); }
});

test('background resets an unchecked anchor even when the selected set is already empty', () => {
  const s = setup((item) => item.path);
  try {
    interaction(s, s.items[0], {}, 'row-select-checkbox');
    interaction(s, s.items[0], {}, 'row-select-checkbox');
    assert.equal(s.render().selected.size, 0);
    s.click([]);
    interaction(s, s.items[2], { shiftKey: true });
    assert.deepEqual([...s.render().selected], ['c.txt']);
  } finally { s.cleanup(); }
});

test('Shift does not start long-press selection or intercept controls and disabled lists', () => {
  const s = setup((item) => item.path);
  try {
    const handlers = s.render().itemInteractionProps(s.items[0]);
    handlers.onPointerDown({ button: 0, shiftKey: true, target: { closest: () => null } });
    assert.equal(s.timers.size, 0);
    for (const target of ['input', 'button', 'label', 'select', 'textarea', 'summary', '.table-actions', '.action-icon']) {
      const event = { shiftKey: true, target: { closest: (selector) => selector.split(',').some((part) => part.trim() === target) ? {} : null },
        preventDefault() { assert.fail('control default prevented'); }, stopPropagation() { assert.fail('control intercepted'); } };
      handlers.onMouseDownCapture(event); handlers.onClickCapture(event);
    }
    s.props.enabled = false; s.render();
    interaction(s, s.items[1], { shiftKey: true }, 'row-select-checkbox');
    s.render().clearSelection();
    const event = interaction(s, s.items[2], { shiftKey: true });
    assert.equal(event.prevented, false);
    assert.equal(s.render().selected.size, 0);
  } finally { s.cleanup(); }
});
