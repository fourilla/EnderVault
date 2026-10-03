import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import * as completion from '../src/shared/search/query-completion.ts';

async function compile(file) {
  const result = await build({ configFile: false, logLevel: 'silent', build: {
    write: false, minify: false, lib: { entry: fileURLToPath(new URL(`../src/shared/search/${file}`, import.meta.url)), formats: ['cjs'] },
    rolldownOptions: { external: (_id, importer) => Boolean(importer) },
  } });
  return (Array.isArray(result) ? result[0] : result).output.find((item) => item.type === 'chunk').code;
}
const inputCode = await compile('useSearchInput.ts');
const assistanceCode = await compile('useSearchAssistance.ts');
const panelCode = await compile('SearchSuggestions.tsx');
const schema = { scope: 'files', limits: { maxLength: 4096 }, fields: [
  { key: 'name', label: 'Name', type: 'TEXT', values: [] },
  { key: 'path', label: 'Path', type: 'PATH', values: [] },
  { key: 'type', label: 'Type', type: 'ENUM', values: ['file', 'directory'] },
] };

function harness() {
  const slots = [], effects = [];
  let cursor = 0, dirty = true;
  const same = (a, b) => a?.length === b?.length && a.every((value, i) => Object.is(value, b[i]));
  const effect = (run, deps) => {
    const index = cursor++, old = slots[index];
    if (!old || !same(old.deps, deps)) {
      const next = { deps, cleanup: old?.cleanup };
      slots[index] = next;
      effects.push(() => { next.cleanup?.(); next.cleanup = run(); });
    }
  };
  const react = {
    useState(initial) {
      const index = cursor++;
      slots[index] ??= { value: typeof initial === 'function' ? initial() : initial };
      return [slots[index].value, (next) => {
        const value = typeof next === 'function' ? next(slots[index].value) : next;
        if (!Object.is(value, slots[index].value)) { slots[index].value = value; dirty = true; }
      }];
    },
    useRef(value) { const index = cursor++; return slots[index] ??= { current: value }; },
    useId() { const index = cursor++; return slots[index] ??= 'search-list'; },
    useMemo(create, deps) {
      const index = cursor++;
      if (!slots[index] || !same(slots[index].deps, deps)) slots[index] = { deps, value: create() };
      return slots[index].value;
    },
    useEffect: effect, useLayoutEffect: effect,
  };
  return { react,
    render(run) {
      let result, count = 0;
      do {
        assert.ok(++count < 25, 'No render loop');
        dirty = false; cursor = 0; result = run();
        effects.splice(0).forEach((run) => run());
      } while (dirty);
      return result;
    },
    dispose() { slots.forEach((slot) => slot?.cleanup?.()); },
  };
}

function events() {
  const listeners = new Map();
  return {
    addEventListener(type, callback) { const set = listeners.get(type) ?? new Set(); set.add(callback); listeners.set(type, set); },
    removeEventListener(type, callback) { listeners.get(type)?.delete(callback); },
    emit(type, event = {}) { [...listeners.get(type) ?? []].forEach((callback) => callback(event)); },
    size() { return [...listeners.values()].reduce((sum, set) => sum + set.size, 0); },
  };
}

function load(code, modules, globals = {}) {
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, ...globals, require(id) {
    assert.ok(id in modules, `Unexpected import ${id}`); return modules[id];
  } });
  return module.exports;
}
const flush = async () => { await new Promise(setImmediate); };

function inputSetup(t, value = '') {
  const h = harness(), doc = { ...events(), visibilityState: 'visible', fullscreenElement: null, querySelector: () => null };
  const win = events(), changes = [], focus = [], ranges = [];
  const options = { scope: 'files', owner: 'files-1', hidden: 'hide', value, disabled: false, open: true,
    onChange(value) { changes.push(value); options.value = value; },
    onOpen() { options.open = true; }, onClose() { options.open = false; } };
  const app = load(inputCode, { react: h.react, './query-completion': completion,
    './useSearchAssistance': { useSearchAssistance({ query, caret, selectionEnd, enabled }) {
      const context = enabled ? completion.completionContext(query, caret, selectionEnd, schema) : null;
      return { context, options: completion.suggestionsFor(context, enabled ? schema : null), hint: null, loading: false, unavailable: false };
    } },
  }, { document: doc, window: win });
  const input = { selectionStart: value.length, selectionEnd: value.length,
    focus(options) { focus.push(options); }, setSelectionRange(start, end) { ranges.push([start, end]); } };
  const render = () => h.render(() => app.useSearchInput(options));
  let result = render();
  result.inputProps.ref.current = input;
  result.rootRef.current = { contains: (target) => target === input };
  result.inputProps.onFocus({ currentTarget: input });
  result = render();
  const key = (name, extra = {}) => {
    let prevented = false, stopped = false;
    render().inputProps.onKeyDown({ key: name, nativeEvent: {}, keyCode: 0, ...extra,
      preventDefault() { prevented = true; }, stopPropagation() { stopped = true; } });
    return { prevented, stopped };
  };
  t.after(() => h.dispose());
  return { render, options, input, changes, focus, ranges, key, doc, win, h };
}

test('Arrow selects a tag, Enter completes without submission, and focus/caret stay in the input', (t) => {
  const app = inputSetup(t);
  assert.equal(app.key('ArrowDown').prevented, true);
  assert.equal(app.render().activeIndex, 0);
  assert.equal(app.key('Enter').prevented, true);
  assert.deepEqual(app.changes, ['name:']);
  assert.equal(app.render().activeIndex, -1);
  assert.deepEqual(app.ranges.at(-1), [5, 5]);
  assert.equal(app.focus.at(-1).preventScroll, true);
  assert.equal(app.options.open, true);
});

test('enum completion closes suggestions; unselected Enter remains the existing search submit', (t) => {
  const app = inputSetup(t, 'type:fi');
  assert.equal(app.key('Enter').prevented, false);
  assert.equal(app.options.open, false);
  app.options.open = true;
  assert.equal(app.key('ArrowDown').prevented, true);
  assert.equal(app.key('Enter').prevented, true);
  assert.deepEqual(app.changes, ['type:file']);
  assert.equal(app.render().visible, false);
  assert.equal(app.key('Enter').prevented, false);
});

test('Escape closes only the popup and can be reopened without erasing the query', (t) => {
  const app = inputSetup(t, 'na');
  assert.deepEqual(app.key('Escape'), { prevented: true, stopped: true });
  assert.equal(app.options.value, 'na');
  assert.deepEqual(app.changes, []);
  assert.equal(app.render().visible, false);
  app.key('ArrowDown');
  assert.equal(app.render().visible, true);
});

test('IME, modifier keys and text selection retain native input behavior', (t) => {
  const app = inputSetup(t);
  for (const extra of [{ nativeEvent: { isComposing: true } }, { keyCode: 229 }, { ctrlKey: true }, { metaKey: true }, { altKey: true }]) {
    assert.equal(app.key('ArrowDown', extra).prevented, false);
  }
  app.input.selectionStart = 0; app.input.selectionEnd = 4;
  app.render().inputProps.onSelect({ currentTarget: app.input });
  assert.equal(app.key('ArrowDown').prevented, false);
  assert.equal(app.key('a', { ctrlKey: true }).prevented, false);
});

test('blur, outside pointer and hidden tabs close suggestions; listeners are removed on unmount', (t) => {
  const app = inputSetup(t);
  app.doc.emit('pointerdown', { target: app.input });
  assert.equal(app.options.open, true);
  app.doc.emit('pointerdown', { target: {} });
  assert.equal(app.render().visible, false);
  app.options.open = true; app.render();
  app.doc.visibilityState = 'hidden'; app.doc.emit('visibilitychange');
  assert.equal(app.render().visible, false);
  app.options.open = true; app.doc.visibilityState = 'visible'; app.render();
  app.render().rootProps.onBlur({ relatedTarget: {} });
  assert.equal(app.render().visible, false);
  app.h.dispose();
  assert.equal(app.doc.size() + app.win.size(), 0);
});

test('moving focus within the search form keeps it open; leaving its submit button closes it', (t) => {
  const app = inputSetup(t), submitButton = {};
  app.render().rootRef.current = { contains: (target) => target === app.input || target === submitButton };
  app.render().rootProps.onBlur({ relatedTarget: submitButton });
  assert.equal(app.options.open, true);
  app.render().rootProps.onBlur({ relatedTarget: {} });
  assert.equal(app.render().visible, false);
});

test('dialog/fullscreen take priority; disabled routes cannot change query or select stale suggestions', (t) => {
  const app = inputSetup(t);
  app.key('ArrowDown');
  app.doc.querySelector = () => ({});
  assert.equal(app.key('Enter').prevented, false);
  assert.deepEqual(app.changes, []);
  app.render().choose(0);
  assert.deepEqual(app.changes, []);
  app.doc.querySelector = () => null;
  app.options.disabled = true;
  app.render().inputProps.onChange({ currentTarget: { value: 'ignored' } });
  assert.deepEqual(app.changes, []);
});

test('routes without a schema opt-in retain plain search and expose no completion controls', (t) => {
  const app = inputSetup(t);
  app.options.scope = undefined;
  const result = app.render();
  assert.equal(result.visible, false);
  assert.equal(result.inputProps.role, undefined);
  assert.equal(app.key('ArrowDown').prevented, false);
  result.inputProps.onChange({ currentTarget: { value: 'ordinary search', selectionStart: 15, selectionEnd: 15 } });
  assert.deepEqual(app.changes, ['ordinary search']);
});

test('an unfinished caret restoration cannot target a newly registered route', (t) => {
  const app = inputSetup(t);
  app.options.onChange = (value) => app.changes.push(value);
  app.render().choose(0);
  app.render();
  assert.deepEqual(app.changes, ['name:']);
  assert.equal(app.ranges.length, 0);
  app.options.owner = 'files-2'; app.options.value = 'name:';
  app.render();
  assert.equal(app.ranges.length, 0);
});

function assistanceSetup(t) {
  const h = harness(), calls = [], timers = new Map();
  let id = 0;
  const request = (type, args) => new Promise((resolve, reject) => calls.push({ type, args, resolve, reject }));
  const client = { schema: (...args) => request('schema', args), directories: (...args) => request('directories', args) };
  const app = load(assistanceCode, { react: h.react, './query-completion': completion,
    './search-assistance-api': { createSearchAssistanceClient: () => client } }, {
    AbortController, setTimeout: (callback) => { timers.set(++id, callback); return id; }, clearTimeout: (id) => timers.delete(id),
  });
  const options = { scope: 'files', owner: 'files-1', query: '', caret: 0, selectionEnd: 0, hidden: 'hide', enabled: false };
  const render = () => h.render(() => app.useSearchAssistance(options));
  const runTimers = () => { const jobs = [...timers.values()]; timers.clear(); jobs.forEach((run) => run()); };
  t.after(() => h.dispose());
  return { render, options, calls, timers, runTimers, h };
}

test('schema is loaded only for an opted-in active input, not during page loading or query typing', async (t) => {
  const app = assistanceSetup(t);
  assert.equal(app.render().loading, false);
  assert.equal(app.calls.length, 0);
  app.options.enabled = true;
  assert.equal(app.render().loading, true);
  assert.equal(app.calls.length, 1);
  app.calls[0].resolve(schema); await flush();
  assert.equal(app.render().options.length, 3);
  app.options.query = 'na'; app.options.caret = app.options.selectionEnd = 2;
  assert.equal(app.render().options[0].label, 'name:');
  assert.equal(app.calls.length, 1);
});

test('route changes abort old schema requests and never display stale candidates, even on a late response', async (t) => {
  const app = assistanceSetup(t);
  app.options.enabled = true; app.render();
  app.options.owner = 'recent-2'; app.options.scope = undefined;
  assert.equal(app.render().options.length, 0);
  assert.equal(app.calls[0].args[1].aborted, true);
  app.calls[0].resolve(schema); await flush();
  assert.equal(app.render().options.length, 0);
});

test('path suggestions debounce one parent and abort old caret, hidden-policy and route requests', async (t) => {
  const app = assistanceSetup(t);
  app.options.enabled = true; app.render(); app.calls[0].resolve(schema); await flush();
  app.options.query = 'path:photos/'; app.options.caret = app.options.selectionEnd = 12;
  app.render(); assert.equal(app.calls.length, 1); assert.equal(app.timers.size, 1);
  app.runTimers(); assert.equal(app.calls[1].args[0], 'photos'); assert.equal(app.calls[1].args[1], 'hide');
  app.options.query = 'path:photos/s'; app.options.caret = app.options.selectionEnd = 13; app.render();
  assert.equal(app.calls[1].args[2].aborted, true);
  app.calls[1].resolve([{ name: 'stale', path: 'photos/stale', type: 'directory' }]); await flush();
  assert.equal(app.render().options.length, 0);
  app.runTimers(); app.options.hidden = 'show'; app.render();
  assert.equal(app.calls[2].args[2].aborted, true);
  app.runTimers(); assert.equal(app.calls[3].args[1], 'show');
  app.calls[3].resolve([{ name: 'summer', path: 'photos/summer', type: 'directory' }]); await flush();
  assert.equal(app.render().options[0].label, 'summer');
  app.options.enabled = false;
  assert.equal(app.render().options.length, 0);
  assert.equal(app.calls[3].args[2].aborted, true);
});

test('assistance failures stay local and optional, with no recursive searches or automatic retries', async (t) => {
  const app = assistanceSetup(t);
  app.options.enabled = true; app.render();
  app.calls[0].reject(new Error('Connection lost')); await flush();
  const state = app.render();
  assert.equal(state.unavailable, true);
  assert.equal(state.loading, false);
  assert.equal(state.options.length, 0);
  assert.equal(app.calls.length, 1);
});

test('pending debounce is canceled when input closes or becomes a non-path token', async (t) => {
  const app = assistanceSetup(t);
  app.options.enabled = true; app.render(); app.calls[0].resolve(schema); await flush();
  app.options.query = 'path:'; app.options.caret = app.options.selectionEnd = 5; app.render();
  assert.equal(app.timers.size, 1);
  app.options.query = 'type:'; app.render();
  assert.equal(app.timers.size, 0);
  app.runTimers(); assert.equal(app.calls.length, 1);
});

test('a directory lookup failure keeps schema hints and does not prevent a normal search', async (t) => {
  const app = assistanceSetup(t);
  app.options.enabled = true; app.render(); app.calls[0].resolve(schema); await flush();
  app.options.query = 'path:missing/'; app.options.caret = app.options.selectionEnd = 13;
  app.render(); app.runTimers();
  app.calls[1].reject(new Error('Not found')); await flush();
  const state = app.render();
  assert.equal(state.unavailable, true);
  assert.equal(state.loading, false);
  assert.match(state.hint, /Vault-relative/);
  assert.equal(state.options.length, 0);
  assert.equal(app.calls.length, 2);
});

test('suggestion rows preserve input focus and keyboard scrolling changes only the inner list', () => {
  const h = harness();
  const jsx = (type, props) => ({ type, props });
  const app = load(panelCode, { react: h.react, 'react/jsx-runtime': { jsx, jsxs: jsx }, './search-assistance.css': {} });
  let chosen;
  const props = { listId: 'suggestions', activeIndex: -1, options: [{ label: 'name:', detail: 'TEXT' }],
    hint: null, loading: false, unavailable: false, choose: (index) => { chosen = index; } };
  const render = () => h.render(() => app.SearchSuggestions(props));
  const root = render(), list = root.props.children[0];
  let prevented = false;
  const row = list.props.children[0];
  row.props.onPointerDown({ preventDefault() { prevented = true; } });
  row.props.onClick();
  assert.equal(prevented, true); assert.equal(chosen, 0);
  const inner = { scrollTop: 0, clientHeight: 50, children: [{ offsetTop: 100, offsetHeight: 30 }] };
  list.props.ref.current = inner; props.activeIndex = 0; render();
  assert.equal(inner.scrollTop, 80);
  assert.equal(row.props.tabIndex, -1);
  h.dispose();
});
