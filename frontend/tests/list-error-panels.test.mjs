import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { readFileSync } from 'node:fs';
import { build } from 'vite';
import * as React from 'react';
import * as jsx from 'react/jsx-runtime';
import { renderToStaticMarkup } from 'react-dom/server';
import { linkListStub } from './helpers/link-list-stubs.mjs';

const pages = [
  { file: 'favorites/FavoritesApp', name: 'FavoritesApp', snapshot: { items: [] }, errorSlot: 2, tokenSlot: 3 },
  { file: 'trash/TrashApp', name: 'TrashApp', snapshot: { items: [] }, errorSlot: 2, tokenSlot: 3 },
  { file: 'shares/SharedLinksApp', name: 'SharedLinksApp', snapshot: [], errorSlot: 1, tokenSlot: 3 },
];

async function compile(file) {
  const result = await build({ configFile: false, logLevel: 'silent', build: {
    write: false, minify: false,
    lib: { entry: fileURLToPath(new URL(`../src/${file}.tsx`, import.meta.url)), formats: ['cjs'] },
    rolldownOptions: { external: (_id, importer) => Boolean(importer) },
  } });
  return (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;
}

function evaluate(code, dependencies) {
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, AbortController, URLSearchParams, require(id) {
    if (id === 'react/jsx-runtime') return jsx;
    if (id.endsWith('.css')) return {};
    if (id.endsWith('/LoadingState')) return loadingModule;
    return dependencies(id);
  } });
  return module.exports;
}

const loadingModule = evaluate(await compile('shared/layout/LoadingState'), id => { throw new Error(id); });

test('shared option rows preserve checkbox and switch semantics, values and descriptions', async () => {
  const { OptionRow } = evaluate(await compile('shared/forms/OptionRow'), id => {
    assert.equal(id, 'react');
    return { ...React, useId: () => 'toggle-description' };
  });
  let changed;
  const element = OptionRow({ label: 'Scan area', description: 'Area description', checked: true,
    disabled: true, onChange: (value) => { changed = value; } });
  const html = renderToStaticMarkup(element);
  assert.doesNotMatch(html, /role="switch"/);
  assert.match(html, /option-input-checkbox/);
  assert.match(html, /disabled=""/);
  assert.match(html, /checked=""/);
  assert.match(html, /aria-describedby="toggle-description"/);
  assert.match(html, /id="toggle-description"/);
  const input = element.props.children[1];
  input.props.onChange({ currentTarget: { checked: false } });
  assert.equal(changed, false);
  const switchElement = OptionRow({ label: 'Enabled', control: 'switch', checked: false,
    onChange: (value) => { changed = value; } });
  const switchHtml = renderToStaticMarkup(switchElement);
  assert.match(switchHtml, /role="switch"/);
  assert.match(switchHtml, /option-input-switch/);
  assert.doesNotMatch(switchHtml, /checked=""|disabled=""|aria-describedby/);
  switchElement.props.children[1].props.onChange({ currentTarget: { checked: true } });
  assert.equal(changed, true);
  const forms = readFileSync(new URL('../../src/main/resources/static/css/components/forms.css', import.meta.url), 'utf8');
  const settings = readFileSync(new URL('../../src/main/resources/static/css/pages/settings.css', import.meta.url), 'utf8');
  assert.match(forms, /:is\(\.option-input-switch, \.settings-switch-input\):checked/);
  assert.doesNotMatch(settings, /\.settings-switch-input::before/);
});

test('shared loading states provide accessible labels, spacing and compact sizing', () => {
  const { LoadingState } = loadingModule;
  const normal = renderToStaticMarkup(React.createElement(LoadingState, { label: 'Loading page...' }));
  assert.match(normal, /role="status"/);
  assert.match(normal, /aria-live="polite"/);
  assert.match(normal, /aria-hidden="true"/);
  assert.match(normal, /<span>Loading page\.\.\.<\/span>/);
  assert.doesNotMatch(normal, /loading-state-compact/);
  const compact = renderToStaticMarkup(React.createElement(LoadingState, { label: 'Loading settings...', compact: true }));
  assert.match(compact, /loading-state-compact/);
  const css = readFileSync(new URL('../src/shared/layout/loading-state.css', import.meta.url), 'utf8');
  assert.match(css, /gap: var\(--space-lg\)/);
  assert.match(css, /justify-content: center/);
  assert.match(css, /prefers-reduced-motion: reduce/);
});

const { PageErrorPanel } = evaluate(await compile('shared/layout/PageErrorPanel'), id => {
  assert.equal(id, 'react');
  return React;
});

test('settings load state keeps the editor at the same position across refresh failures', async () => {
  const { SettingsLoadState } = evaluate(await compile('settings/components/SettingsLoadState'), id => {
    assert.ok(id.endsWith('/PageErrorPanel'));
    return { PageErrorPanel };
  });
  const editor = React.createElement('input', { defaultValue: 'Unsaved value' });
  let retries = 0;
  const renderState = (loaded, error) => SettingsLoadState({ loaded, error,
    refresh: () => retries++, loadingLabel: 'Loading settings...', children: loaded ? editor : null });
  const normal = renderState(true, '');
  const failed = renderState(true, 'Offline');
  assert.match(normal.props.className, /settings-load-state/);
  assert.equal(failed.props.className, normal.props.className);
  assert.equal(renderState(false, 'Offline').props.className, normal.props.className);
  assert.equal(normal.props.children[2], editor);
  assert.equal(failed.props.children[2], editor);
  assert.match(renderToStaticMarkup(failed), /Showing the last loaded values/);
  findPanel(failed).props.actions.props.onClick();
  assert.equal(retries, 1);
  assert.doesNotMatch(renderToStaticMarkup(renderState(false, 'Forbidden')), /Unsaved value|last loaded/);
  assert.match(renderToStaticMarkup(renderState(false, '')), /role="status"/);
});

test('settings heading spacing belongs to the load region without doubling form spacing', () => {
  const css = readFileSync(new URL('../src/settings/settings-app.css', import.meta.url), 'utf8');
  assert.match(css, /\.settings-load-state\s*\{\s*padding-top: var\(--space-2xl\);\s*\}/);
  assert.match(css, /\.settings-load-state > \.settings-spa-form\s*\{\s*padding-top: 0;\s*\}/);
});

test('all settings sections share load feedback while saving retains its own error notification', () => {
  for (const name of ['General', 'Advanced', 'Account', 'Bookmark', 'FileRequest', 'Session', 'Telegram', 'Vpn', 'Passkey']) {
    const source = readFileSync(new URL(`../src/settings/sections/${name}Settings.tsx`, import.meta.url), 'utf8');
    assert.match(source, /<SettingsLoadState loaded=\{snapshot !== null\} error=\{error\} refresh=\{refresh\}/);
    assert.doesNotMatch(source, /settings-spa-error/);
  }
  const query = readFileSync(new URL('../src/settings/hooks/useSettingsSnapshot.ts', import.meta.url), 'utf8');
  assert.doesNotMatch(query, /showError/);
  assert.match(query, /if \(!canRetainSnapshot\(reason\)\) setStored\(null\)/);
  const editor = readFileSync(new URL('../src/settings/hooks/useSettingsEditor.ts', import.meta.url), 'utf8');
  assert.match(editor, /showError\(error\)/);
});

function findPanel(node) {
  if (!React.isValidElement(node)) return undefined;
  if (node.type === PageErrorPanel) return node;
  return React.Children.toArray(node.props.children).map(findPanel).find(Boolean);
}

test('remote defaults retry preserves edited inputs and task retry uses only the task provider', async () => {
  const states = [], refs = [], effects = [];
  let index = 0, refIndex = 0, taskRefreshes = 0, resolveDefaults;
  const { RemoteDownloadApp } = evaluate(await compile('remote-download/RemoteDownloadApp'), id => {
    if (id === 'react') return {
      useState(initial) { const slot = index++; if (!(slot in states)) states[slot] = initial;
        return [states[slot], value => { states[slot] = typeof value === 'function' ? value(states[slot]) : value; }]; },
      useRef(initial) { const slot = refIndex++; return refs[slot] ??= { current: initial }; },
      useEffect: run => effects.push(run),
    };
    if (id.endsWith('/PageErrorPanel')) return { PageErrorPanel };
    if (id.endsWith('/AdminAppContext')) return { useAdminApp: () => ({ bootstrap: { outboundRoute: { label: 'Direct' } } }) };
    if (id.endsWith('/RemoteDownloadTasksContext')) return { useRemoteDownloadTasks: () => ({
      tasks: [], error: 'Task fetch failed', refresh: () => taskRefreshes++, trackStarted() {},
    }) };
    if (id.endsWith('/remote-download-api')) return { loadRemoteDownloadPage: () => new Promise(resolve => { resolveDefaults = resolve; }) };
    return {};
  });
  const walk = (node, predicate) => {
    if (!React.isValidElement(node)) return undefined;
    if (predicate(node)) return node;
    return React.Children.toArray(node.props.children).map(child => walk(child, predicate)).find(Boolean);
  };
  const tree = RemoteDownloadApp();
  const form = walk(tree, node => node.type === 'form');
  form.props.onChangeCapture();
  states[1] = 'my destination'; states[3] = 4; states[4] = false;
  effects[0]();
  resolveDefaults({ defaultTargetDirectory: 'server default', skipInspectByDefault: true });
  await Promise.resolve();
  assert.equal(states[1], 'my destination');
  assert.equal(states[3], 4);
  assert.equal(states[4], false);
  assert.equal(states[5], true);
  findPanel(tree).props.actions.props.onClick();
  assert.equal(taskRefreshes, 1);
});

test('detail error panels preserve recovery boundaries and dashboard partial warnings', () => {
  const source = file => readFileSync(new URL(`../src/${file}`, import.meta.url), 'utf8');
  for (const file of ['file-detail/FileDetailApp.tsx', 'file-requests/FileRequestDetailApp.tsx']) {
    const code = source(file);
    assert.match(code, /stale=\{payload !== null\}/);
    assert.match(code, /canRetainSnapshot\(reason\)/);
    assert.doesNotMatch(code, /browser-load-error/);
  }
  const detail = source('file-detail/FileDetailApp.tsx');
  assert.match(detail, /\{failurePanel\}\s*<div>\s*<FileTools payload=\{payload\} \/>/);
  const dashboard = source('dashboard/DashboardApp.tsx');
  assert.match(dashboard, /if \(!payload && summary.error\) return <PageErrorPanel/);
  assert.match(dashboard, /if \(runtime.error\) warnings.push/);
  const bookmark = source('bookmarks/BookmarkDetailApp.tsx');
  assert.match(bookmark, /if \(signal\?\.aborted\) return/);
  assert.match(bookmark, /\[load, refreshToken\]/);
});

test('files and search retry through the existing reload without navigation or selection changes', async () => {
  const { BrowserListing } = evaluate(await compile('files/BrowserListing'), id => {
    if (id.endsWith('/PageErrorPanel')) return { PageErrorPanel };
    if (id.endsWith('/BrowserEntries')) return { EntryGrid: () => null, EntryTable: () => null,
      icon: value => React.createElement('i', { className: value }) };
    if (id.endsWith('/BrowserPagination')) return { BrowserPagination: () => null };
    throw new Error(`Unexpected dependency: ${id}`);
  });
  for (const mode of ['browse', 'search']) {
    for (const payload of [null, { mode, search: { query: 'saved query' }, directories: [], entries: [],
      page: { totalItems: 0 }, preferences: { view: 'grid' } }]) {
      let retries = 0;
      const selected = new Set(['/chosen']);
      const tree = BrowserListing({ payload, currentState: { mode, query: 'saved query' }, loading: false,
        error: 'Fetch failed', selected, reload: () => retries++, navigate: () => assert.fail('must not navigate') });
      const panel = findPanel(tree);
      assert.equal(panel.props.stale, payload !== null);
      assert.equal(panel.props.title, mode === 'search' ? 'Search unavailable' : 'Files unavailable');
      panel.props.actions.props.onClick();
      assert.equal(retries, 1);
      assert.deepEqual([...selected], ['/chosen']);
      assert.match(renderToStaticMarkup(tree), /page-feedback-layout/);
    }
  }
});

test('history-backed lists reuse reload and keep their existing navigation and selection hooks', () => {
  for (const file of ['recent/RecentApp.tsx', 'bookmarks/BookmarksApp.tsx']) {
    const source = readFileSync(new URL(`../src/${file}`, import.meta.url), 'utf8');
    assert.match(source, /<PageErrorPanel[\s\S]*?stale=\{payload !== null\}/);
    assert.match(source, /disabled=\{loading\} onClick=\{reload\}/);
    assert.match(source, /useNavigationScroll\(payload, loading, state.scrollTop, historyKey, ready\)/);
    assert.match(source, /useListingRefresh\(reload\)/);
    assert.doesNotMatch(source, /browser-load-error/);
  }
  const app = readFileSync(new URL('../src/files/BrowserApp.tsx', import.meta.url), 'utf8');
  assert.match(app, /<BrowserListing[\s\S]*?reload=\{reload\}/);
});

test('sticky note retry retains the applied query instead of submitting uncommitted search text', async () => {
  const states = ['unsubmitted text', null, '', 'Fetch failed', 0];
  let index = 0;
  const effects = [];
  const queries = [];
  const { StickyNoteListApp } = evaluate(await compile('sticky-notes/StickyNoteListApp'), id => {
    if (id === 'react') return {
      useEffect: (run, deps) => effects.push({ run, deps }),
      useState() { const slot = index++; return [states[slot], value => {
        states[slot] = typeof value === 'function' ? value(states[slot]) : value;
      }]; },
    };
    if (id === 'react-router-dom') return { useSearchParams: () => [new URLSearchParams('q=applied'), () => assert.fail('no navigation')] };
    if (id.endsWith('/PageErrorPanel')) return { PageErrorPanel };
    if (id.endsWith('/PageHeader')) return { PageHeader: () => null };
    if (id.endsWith('/RouteSearch')) return { useRouteSearch() {} };
    if (id.endsWith('/AppNavigationLink') || id.endsWith('/form-api')) return {};
    if (id.endsWith('/sticky-note-catalog-api')) return { loadStickyNoteCatalog: async query => {
      queries.push(query); return { notes: [] };
    } };
    throw new Error(`Unexpected dependency: ${id}`);
  });
  const tree = StickyNoteListApp();
  findPanel(tree).props.actions.props.onClick();
  assert.equal(states[4], 1);
  assert.equal(states[0], 'unsubmitted text');
  index = 0;
  effects.length = 0;
  StickyNoteListApp();
  const fetchEffect = effects.find(effect => effect.deps?.length === 2);
  assert.deepEqual([...fetchEffect.deps], ['applied', 1]);
  const cleanup = fetchEffect.run();
  await Promise.resolve();
  assert.deepEqual(queries, ['applied']);
  assert.equal(states[3], '');
  assert.deepEqual(states[1], []);
  cleanup();
});

for (const page of pages) {
  const code = await compile(page.file);
  test(`${page.name} shares initial/stale error layout and retries without clearing the snapshot`, () => {
    for (const snapshot of [null, page.snapshot]) {
      const states = [snapshot, false, '', 0, ''];
      states[page.errorSlot] = 'Network unavailable';
      if (page.name === 'SharedLinksApp') {
        states[0] = snapshot === null ? null : { query: '', shares: snapshot };
        states[page.errorSlot] = { query: '', message: 'Network unavailable' };
      }
      if (page.name === 'TrashApp') {
        states[0] = snapshot === null ? null : { query: '', payload: snapshot };
        states[page.errorSlot] = { query: '', message: 'Network unavailable' };
      }
      states[page.tokenSlot] = 0;
      let index = 0;
      const { [page.name]: App } = evaluate(code, id => {
        const listStub = linkListStub(id);
        if (page.name === 'SharedLinksApp' && listStub) return listStub;
        if (id === 'react') return {
          useEffect() {},
          useRef: initial => ({ current: initial }),
          useMemo: factory => factory(),
          useState(initial) {
            const slot = index++;
            if (!(slot in states)) states[slot] = initial;
            return [states[slot], value => { states[slot] = typeof value === 'function' ? value(states[slot]) : value; }];
          },
        };
        if (id === 'react-router-dom') return { Link: ({ children }) => React.createElement('a', null, children), useLocation: () => ({ key: 'test' }),
          useSearchParams: () => [new URLSearchParams(), () => assert.fail('no navigation')] };
        if (id.endsWith('/RouteSearch')) return { useRouteSearch() {} };
        if (id.endsWith('/PageErrorPanel')) return { PageErrorPanel };
        if (id.endsWith('/PageHeader')) return { PageHeader: () => null };
        if (id.endsWith('/FloatingPageActions')) return { FloatingPageActions: () => null };
        if (id.endsWith('/OverflowMarquee')) return { OverflowMarquee: ({ text }) => React.createElement('span', null, text) };
        if (id.endsWith('/StableTable')) return { StableTable: ({ children }) => React.createElement('table', null, children) };
        if (id.endsWith('/PathLink')) return { PathLink: ({ path, label }) => React.createElement('a', null, label ?? path) };
        if (id.endsWith('/BrowserEntries')) return { icon: value => React.createElement('i', { className: value }) };
        if (id.endsWith('-api')) return {};
        throw new Error(`Unexpected dependency: ${id}`);
      });
      const tree = App();
      const findPanel = node => {
        if (!React.isValidElement(node)) return undefined;
        if (node.type === PageErrorPanel) return node;
        return React.Children.toArray(node.props.children).map(findPanel).find(Boolean);
      };
      const panel = findPanel(tree);
      assert.ok(panel);
      assert.equal(panel.props.stale, snapshot !== null);
      const html = renderToStaticMarkup(tree);
      assert.match(html, /page-feedback-layout/);
      assert.match(html, /Network unavailable/);
      assert.equal(html.includes('Showing the last loaded values.'), snapshot !== null);
      assert.match(html, /class="icon-text-button"/);
      panel.props.actions.props.onClick();
      assert.equal(states[page.tokenSlot], 1);
      assert.equal(page.name === 'SharedLinksApp' ? states[0]?.shares ?? null
        : page.name === 'TrashApp' ? states[0]?.payload ?? null : states[0], snapshot);
    }
  });
}
