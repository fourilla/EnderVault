import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import * as React from 'react';
import * as jsx from 'react/jsx-runtime';
import { renderToStaticMarkup } from 'react-dom/server';
import { comicPageIndex } from '../src/file-detail/comic-page.ts';

async function compile(file) {
  const result = await build({ configFile: false, logLevel: 'silent', build: { write: false, minify: false,
    lib: { entry: fileURLToPath(new URL(`../src/${file}`, import.meta.url)), formats: ['cjs'] },
    rolldownOptions: { external: (_id, importer) => Boolean(importer) } } });
  return (Array.isArray(result) ? result[0] : result).output.find((item) => item.type === 'chunk').code;
}
function load(code, modules, globals = {}) {
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, URLSearchParams, ...globals, require(id) {
    assert.ok(id in modules, `unexpected dependency: ${id}`);
    return modules[id];
  } });
  return module.exports;
}
const viewerCode = await compile('shared/file-tools/ComicViewer.tsx');
const adminCode = await compile('file-detail/ComicTool.tsx');
const publicCode = await compile('shared-file/SharedComicViewer.tsx');
const entryCode = await compile('shared-file/comic.tsx');
const { ComicViewer } = load(viewerCode, { react: React, 'react/jsx-runtime': jsx });
const manifest = { pageCount: 3, metadata: { present: true, truncated: false,
  rawText: '<script>bad()</script>', entries: [{ name: 'Title', value: '<img onerror=bad()>' }] } };
const props = { name: '<bad>.cbz', manifest, pageUrl: '/s/token/comic/page', page: 1, onPageChange() {} };
const nodes = (element) => React.isValidElement(element)
  ? [element, ...React.Children.toArray(element.props.children).flatMap(nodes)] : [];
function hooks(values = []) {
  const effects = [], writes = [], state = values.slice();
  let index = 0;
  return { effects, writes, state, react: { useEffect: (run) => effects.push(run), useState(initial) {
    const key = index++;
    return [key < values.length ? values[key] : initial, (value) => { writes.push([key, value]); state[key] = value; }];
  } } };
}
const tick = () => new Promise(setImmediate);

test('comic viewer reuses read-only controls and renders metadata as text', () => {
  const html = renderToStaticMarkup(React.createElement(ComicViewer, props));
  assert.match(html, /Page 2 \/ 3/);
  assert.match(html, /src="\/s\/token\/comic\/page\?page=1"/);
  assert.match(html, /&lt;script&gt;bad\(\)&lt;\/script&gt;/);
  assert.match(html, /&lt;img onerror=bad\(\)&gt;/);
  assert.match(html, /alt="&lt;bad&gt;\.cbz page 2"/);
  assert.equal((html.match(/type="button"/g) || []).length, 5);
  assert.doesNotMatch(html, /<script|\/api\/|\/files\/|Extract|Save|data-text-draft/);
});

test('comic navigation clamps numbers without a server mutation', () => {
  const context = hooks(), changed = [];
  const View = load(viewerCode, { react: context.react, 'react/jsx-runtime': jsx }).ComicViewer;
  const tree = nodes(View({ ...props, onPageChange: (page) => changed.push(page) }));
  tree.find((node) => node.props.title === 'Next page').props.onClick();
  const input = tree.find((node) => node.type === 'input');
  ['999', '-5', '2.8', 'bad', '2'].forEach((value) => input.props.onChange({ target: { value } }));
  assert.deepEqual(changed, [2, 2, 0]);
});

test('comic preload is limited to adjacent pages and uses the supplied scope', () => {
  for (const [page, count, expected] of [[0, 3, [1]], [1, 3, [0, 2]], [2, 3, [1]], [0, 0, []]]) {
    const context = hooks(), urls = [];
    const View = load(viewerCode, { react: context.react, 'react/jsx-runtime': jsx }, {
      Image: class { set src(value) { urls.push(value); } },
    }).ComicViewer;
    View({ ...props, page, pageUrl: '/s/token/comic/page?path=a%2Bb&item=book.cbz',
      manifest: { ...manifest, pageCount: count } });
    context.effects[1]();
    assert.deepEqual(urls, expected.map((index) => `/s/token/comic/page?path=a%2Bb&item=book.cbz&page=${index}`));
  }
});

test('comic fullscreen exits on Escape and releases the body lock and listener', () => {
  const context = hooks([true, '']), classes = new Set(), listeners = new Map();
  const View = load(viewerCode, { react: context.react, 'react/jsx-runtime': jsx }, { document: {
    body: { classList: { toggle(name, enabled) { if (enabled) classes.add(name); else classes.delete(name); },
      remove: (name) => classes.delete(name) } },
    addEventListener: (name, run) => listeners.set(name, run),
    removeEventListener: (name, run) => { if (listeners.get(name) === run) listeners.delete(name); },
  } }).ComicViewer;
  View(props);
  const cleanup = context.effects[0]();
  assert.ok(classes.has('is-comic-viewer-fullscreen'));
  listeners.get('keydown')({ key: 'Escape' });
  assert.deepEqual(context.writes, [[0, false]]);
  cleanup();
  assert.equal(classes.size, 0);
  assert.equal(listeners.size, 0);
});

test('empty and failed comic pages display a local message without a broken image', () => {
  const empty = renderToStaticMarkup(React.createElement(ComicViewer, {
    ...props, manifest: { ...manifest, pageCount: 0 }, page: 0,
  }));
  assert.match(empty, /No image pages found/);
  assert.doesNotMatch(empty, /<img|type="button"/);
  const context = hooks([false, '/s/token/comic/page?page=1']);
  const View = load(viewerCode, { react: context.react, 'react/jsx-runtime': jsx }).ComicViewer;
  const failed = renderToStaticMarkup(View(props));
  assert.match(failed, /This comic page could not be loaded/);
  assert.doesNotMatch(failed, /<img/);
  const retryContext = hooks([false, '/s/token/comic/page?page=1']);
  const RetryView = load(viewerCode, { react: retryContext.react, 'react/jsx-runtime': jsx }, {
    Image: class { set src(_value) {} },
  }).ComicViewer;
  RetryView({ ...props, page: 0 });
  retryContext.effects[1]();
  assert.equal(retryContext.state[1], '');
});

test('admin wrapper preserves Router state and query while using the common viewer', () => {
  const navigations = [], location = { pathname: '/files/detail', search: '?path=a.cbz&comicPage=2',
    hash: '#tool', state: { from: 'recent' } };
  const { ComicTool } = load(adminCode, { 'react/jsx-runtime': jsx,
    'react-router-dom': { useLocation: () => location, useNavigate: () => (...args) => navigations.push(args) },
    '../shared/file-tools/ComicViewer': { ComicViewer }, './comic-page': { comicPageIndex },
  });
  const element = ComicTool({ payload: { detail: { name: 'a.cbz' }, comic: { manifest,
    pageUrl: '/files/detail/comic/page?path=a.cbz', pageIndex: 0 } } });
  assert.equal(element.type, ComicViewer);
  assert.equal(element.props.page, 1);
  element.props.onPageChange(2);
  const [url, options] = navigations[0];
  assert.equal(new URLSearchParams(url.search).get('path'), 'a.cbz');
  assert.equal(new URLSearchParams(url.search).get('comicPage'), '3');
  assert.equal(url.hash, '#tool');
  assert.equal(options.state, location.state);
  assert.equal(options.replace, true);
  assert.equal(options.preventScrollReset, true);
});

function publicViewer(context, fetch) {
  return load(publicCode, { react: context.react, 'react/jsx-runtime': jsx,
    '../shared/file-tools/ComicViewer': { ComicViewer } }, { fetch, AbortController, Error }).SharedComicViewer;
}

test('public wrapper fetches the token manifest and renders the exact same viewer', async () => {
  const context = hooks(), payload = { ...manifest, name: 'book.cbz', pageUrl: '/s/token/comic/page' };
  const View = publicViewer(context, async (url, options) => {
    assert.equal(url, '/s/token/comic/manifest');
    assert.equal(options.credentials, 'omit');
    assert.equal(options.mode, 'same-origin');
    assert.equal(options.headers.Accept, 'application/json');
    return { ok: true, headers: new Headers({ 'content-type': 'application/json' }), json: async () => payload };
  });
  assert.match(renderToStaticMarkup(View({ manifestUrl: '/s/token/comic/manifest' })), /Loading comic/);
  context.effects[0]();
  await tick();
  assert.equal(context.state[0], payload);
  const loaded = hooks([payload, '', 0]);
  const element = publicViewer(loaded, () => assert.fail('fetch only in effect'))({ manifestUrl: '/s/token/comic/manifest' });
  assert.equal(element.type, ComicViewer);
  assert.equal(element.props.manifest, payload);
  element.props.onPageChange(2);
  assert.equal(loaded.state[2], 2);
});

test('public manifest errors use the backend notification contract and never inject HTML', async () => {
  for (const [type, body, expected] of [
    ['application/json', { notification: { message: '<script>Denied</script>' } }, '<script>Denied</script>'],
    ['text/html', {}, 'Comic preview could not be loaded.'],
  ]) {
    const context = hooks();
    publicViewer(context, async () => ({ ok: false, headers: new Headers({ 'content-type': type }), json: async () => body }))
      ({ manifestUrl: '/s/token/comic/manifest' });
    context.effects[0]();
    await tick();
    assert.equal(context.state[1], expected);
    const html = renderToStaticMarkup(publicViewer(hooks([null, expected, 0]), () => {})
      ({ manifestUrl: '/s/token/comic/manifest' }));
    assert.doesNotMatch(html, /<script>/);
  }
});

test('public manifest request aborts and ignores late results after unmount', async () => {
  const context = hooks();
  let resolve, signal;
  publicViewer(context, (_url, options) => { signal = options.signal; return new Promise((done) => { resolve = done; }); })
    ({ manifestUrl: '/s/token/comic/manifest' });
  const cleanup = context.effects[0]();
  const count = context.writes.length;
  cleanup();
  assert.equal(signal.aborted, true);
  resolve({ ok: true, headers: new Headers({ 'content-type': 'application/json' }), json: async () => manifest });
  await tick();
  assert.equal(context.writes.length, count);
});

test('public comic bootstrap only mounts a server-provided comic root', () => {
  const component = () => null;
  for (const root of [null, { dataset: {} }, { dataset: { manifestUrl: '/s/token/comic/manifest' } }]) {
    let rendered;
    load(entryCode, { 'react/jsx-runtime': jsx, './SharedComicViewer': { SharedComicViewer: component },
      './preview-disclosure': { mountWhenPreviewOpened: (_container, mount) => mount() },
      'react-dom/client': { createRoot(container) { assert.equal(container, root); return { render(value) { rendered = value; } }; } },
    }, { document: { getElementById: () => root } });
    assert.equal(Boolean(rendered), Boolean(root?.dataset.manifestUrl));
    if (rendered) { assert.equal(rendered.type, component); assert.equal(rendered.props.manifestUrl, root.dataset.manifestUrl); }
  }
});
