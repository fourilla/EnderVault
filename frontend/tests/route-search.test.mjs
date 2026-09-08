import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import test from 'node:test';
import { build } from 'vite';

const result = await build({ configFile: false, logLevel: 'silent',
  build: { write: false, minify: false,
    lib: { entry: fileURLToPath(new URL('../src/app/RouteSearch.tsx', import.meta.url)), formats: ['cjs'] },
    rolldownOptions: { external: (_id, importer) => Boolean(importer) } },
});
const code = (Array.isArray(result) ? result[0] : result).output.find((item) => item.type === 'chunk').code;

// Exercise the registration/cleanup and rendered event handlers without a browser server.
function setup() {
  let registration = null, contextId = 0, key = 'files', cleanup, previousDeps;
  const publish = (next) => { registration = typeof next === 'function' ? next(registration) : next; };
  const jsx = (type, props) => ({ type, props });
  const modules = {
    react: {
      createContext: () => ({ id: contextId++ }),
      useContext: (context) => context.id === 0 ? registration : publish,
      useLayoutEffect(run, deps) {
        if (previousDeps && deps.every((dep, i) => Object.is(dep, previousDeps[i]))) return;
        cleanup?.();
        previousDeps = deps;
        cleanup = run();
      },
    },
    'react/jsx-runtime': { jsx, jsxs: jsx },
    'react-router-dom': { useLocation: () => ({ key }) },
  };
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, require: (id) => {
    assert.ok(id in modules, `Unexpected import ${id}`);
    return modules[id];
  } });
  return { ...module.exports, navigate: (next) => { key = next; }, cleanup: () => cleanup,
    clear: () => { cleanup?.(); cleanup = undefined; previousDeps = undefined; } };
}

function input(form) { return form.props.children[0].props.children[1]; }
function submit(form) {
  let prevented = false;
  form.props.onSubmit({ preventDefault() { prevented = true; } });
  assert.equal(prevented, true, 'Never perform native form navigation');
}

test('unsupported and lazy-loading routes show disabled search without stale values or handlers', () => {
  const app = setup();
  assert.equal(input(app.TopbarSearch()).props.disabled, true);
  let calls = 0;
  app.useRouteSearch({ label: 'Search files', value: 'private filename', onChange() {}, onSubmit() { calls++; } });
  const form = app.TopbarSearch();
  assert.equal(input(form).props.value, 'private filename');
  submit(form);
  assert.equal(calls, 1);
  app.navigate('settings');
  const unavailable = app.TopbarSearch();
  assert.equal(input(unavailable).props.disabled, true);
  assert.equal(input(unavailable).props.value, '');
  submit(unavailable);
  assert.equal(calls, 1);
  app.clear();
  assert.equal(input(app.TopbarSearch()).props.disabled, true);
});

test('query edits only call the page setter; submit uses the latest handler and supports history restoration', () => {
  const app = setup();
  const changes = [], submitted = [];
  const config = (value) => ({ label: 'Search bookmarks', value,
    onChange: (next) => changes.push(next), onSubmit: () => submitted.push(value) });
  app.useRouteSearch(config('old'));
  input(app.TopbarSearch()).props.onChange({ currentTarget: { value: 'new' } });
  assert.deepEqual(changes, ['new']);
  assert.deepEqual(submitted, []);
  app.useRouteSearch(config('new'));
  submit(app.TopbarSearch());
  app.navigate('back');
  app.useRouteSearch(config('restored'));
  assert.equal(input(app.TopbarSearch()).props.value, 'restored');
  submit(app.TopbarSearch());
  assert.deepEqual(submitted, ['new', 'restored']);
});

test('late cleanup cannot remove a newer registration and temporarily disabled searches cannot submit', () => {
  const app = setup();
  app.useRouteSearch({ label: 'Search files', value: '', onChange() {}, onSubmit() {} });
  const oldCleanup = app.cleanup();
  app.navigate('logs');
  let calls = 0;
  app.useRouteSearch({ label: 'Search activity logs', value: 'test', disabled: true,
    onChange() { calls++; }, onSubmit() { calls++; } });
  oldCleanup();
  const form = app.TopbarSearch();
  assert.equal(form.props['aria-label'], 'Search activity logs');
  assert.equal(input(form).props.disabled, true);
  submit(form);
  input(form).props.onChange({ currentTarget: { value: 'ignored' } });
  assert.equal(calls, 0);
});

test('note reset delegates to the page and registration separates publisher from reader', () => {
  const app = setup();
  let resets = 0;
  app.useRouteSearch({ label: 'Search notes', value: 'note', onChange() {}, onSubmit() {}, onReset() { resets++; } });
  app.TopbarSearch().props.children[1].props.onClick();
  assert.equal(resets, 1);
  const source = readFileSync(new URL('../src/app/RouteSearch.tsx', import.meta.url), 'utf8');
  assert.match(source, /SearchRegistrationContext.Provider value=\{setSearch\}/);
  assert.match(source, /SearchContext.Provider value=\{search\}/);
});

test('simple SPA searches use the topbar while logs keep their combined filter form', () => {
  for (const file of ['files/BrowserApp', 'recent/RecentApp', 'bookmarks/BookmarksApp',
    'sticky-notes/StickyNoteListApp']) {
    const source = readFileSync(new URL(`../src/${file}.tsx`, import.meta.url), 'utf8');
    assert.match(source, /useRouteSearch\(/, file);
    assert.doesNotMatch(source, /className="search-form"|className="search-field"/, file);
  }
  const toolbar = readFileSync(new URL('../src/files/BrowserToolbar.tsx', import.meta.url), 'utf8');
  assert.doesNotMatch(toolbar, /search-form|submitSearch|setSearchText/);
  const logs = readFileSync(new URL('../src/activity-logs/ActivityLogsApp.tsx', import.meta.url), 'utf8');
  assert.doesNotMatch(logs, /useRouteSearch|filterFormRef/);
  assert.match(logs, /className="log-filter-form" onSubmit=\{applyFilters\}/);
  assert.match(logs, /className="log-filter-search"/);
  assert.match(logs, /value=\{draft.text\}/);
});
