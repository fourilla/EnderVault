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
  const slots = [], effects = [], listeners = new Map();
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
  vm.runInNewContext(code, { module, exports: module.exports, document, require: () => react });
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
  return { items, render, click, props, cleanup: () => slots.forEach((slot) => slot?.cleanup?.()) };
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
    for (const target of ['[data-context-item="true"]', '.toolbar', '.floating-page-actions', 'dialog']) {
      s.click([target]); assert.equal(s.render().selected.size, 1);
    }
    s.click([]); assert.equal(s.render().selected.size, 0);
    s.render().selectItem(s.items[1], true);
    s.props.locationKey = 'next-page'; s.render();
    assert.equal(s.render().selected.size, 0);
  } finally { s.cleanup(); }
});
