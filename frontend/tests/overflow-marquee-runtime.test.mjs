import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';

const result = await build({ configFile: false, logLevel: 'silent', build: {
  write: false, minify: false,
  lib: { entry: fileURLToPath(new URL('../src/shared/layout/OverflowMarquee.tsx', import.meta.url)), formats: ['cjs'], cssFileName: 'overflow-marquee-test' },
  rolldownOptions: { external: ['react', 'react/jsx-runtime'] },
} });
const code = (Array.isArray(result) ? result[0] : result).output.find((item) => item.type === 'chunk').code;

function setup(contentWidth, availableWidth, useObserver = true) {
  let index = 0;
  let callback;
  let disconnected = false;
  const slots = [], effects = [], observed = [], listeners = new Map();
  const viewport = { getBoundingClientRect: () => ({ width: availableWidth }) };
  const track = {};
  const element = (type, props, ...children) => ({ type, props: { ...props, ...(children.length ? { children } : {}) } });
  const react = {
    createElement: element,
    useRef(initial) { const i = index++; return slots[i] ??= { current: initial }; },
    useState(initial) {
      const i = index++;
      if (!(i in slots)) slots[i] = initial;
      return [slots[i], (value) => { slots[i] = value; }];
    },
    useLayoutEffect(fn, deps) {
      const i = index++;
      if (!slots[i] || deps.some((dep, j) => dep !== slots[i].deps[j])) {
        slots[i]?.cleanup?.();
        const effect = { deps, cleanup: null };
        slots[i] = effect;
        effects.push(() => { effect.cleanup = fn(); });
      }
    },
  };
  const document = { createRange: () => ({
    selectNodeContents: (node) => assert.equal(node, track),
    getBoundingClientRect: () => ({ width: contentWidth }),
  }) };
  const window = {
    addEventListener: (name, fn) => listeners.set(name, fn),
    removeEventListener: (name, fn) => { if (listeners.get(name) === fn) listeners.delete(name); },
  };
  const module = { exports: {} };
  const context = { module, exports: module.exports, document, window, React: react,
    require: (name) => name === 'react' ? react : { jsx: element, jsxs: element },
  };
  if (useObserver) context.ResizeObserver = class {
    constructor(fn) { callback = fn; }
    observe(node) { observed.push(node); }
    disconnect() { disconnected = true; }
  };
  vm.runInNewContext(code, context);
  const render = (text = 'Conflicts, recent items, and trash retention') => {
    index = 0;
    const view = module.exports.OverflowMarquee({ text });
    slots[0].current = viewport;
    slots[1].current = track;
    effects.splice(0).forEach((effect) => effect());
    return view;
  };
  render();
  return { render, observed, listeners,
    resize(content, available) {
      contentWidth = content; availableWidth = available;
      (callback || listeners.get('resize'))();
    },
    cleanup() { slots.forEach((slot) => slot?.cleanup?.()); return disconnected; },
  };
}

for (const overflow of [0.125, 0.5, 1, 1.125, 20]) {
  test(`marquee detects ${overflow}px of overflow, including clipped boundary text`, () => {
    const s = setup(220.25 + overflow, 220.25);
    try {
      const view = s.render();
      assert.match(view.props.className, /is-overflowing/);
      assert.equal(view.props.style['--marquee-distance'], `-${Math.ceil(overflow)}px`);
      assert.equal(view.props.title, 'Conflicts, recent items, and trash retention');
    } finally { s.cleanup(); }
  });
}

test('fitting text at fractional widths does not slide because of integer rounding', () => {
  for (const [content, available] of [[220.25, 220.25], [220.5, 220.75], [180, 220.75], [0, 0]]) {
    const s = setup(content, available);
    try {
      const view = s.render();
      assert.equal(view.props.className, 'overflow-marquee');
      assert.deepEqual(Object.keys(view.props.style), []);
    } finally { s.cleanup(); }
  }
});

test('resize and text updates remeasure overflow and release both observers', () => {
  const s = setup(220.5, 220.25);
  assert.equal(s.observed.length, 2);
  s.resize(220.5, 300);
  assert.equal(s.render().props.className, 'overflow-marquee');
  s.resize(300.125, 300);
  assert.match(s.render('Longer description').props.className, /is-overflowing/);
  assert.equal(s.cleanup(), true);
});

test('resize fallback handles boundary overflow and removes its listener', () => {
  const s = setup(220.5, 220.25, false);
  assert.match(s.render().props.className, /is-overflowing/);
  s.resize(220.5, 250);
  assert.equal(s.render().props.className, 'overflow-marquee');
  s.cleanup();
  assert.equal(s.listeners.size, 0);
});
