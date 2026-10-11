import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import * as jsx from 'react/jsx-runtime';
import { createMemoryRouter } from 'react-router-dom';
import { detailQueryKey } from '../src/file-detail/file-detail-api.ts';
import { createHookHarness } from './helpers/react-hooks.mjs';

async function compile(file) {
  const result = await build({ configFile: false, logLevel: 'silent', build: { write: false, minify: false,
    lib: { entry: fileURLToPath(new URL(`../src/${file}`, import.meta.url)), formats: ['cjs'] },
    rolldownOptions: { external: (_id, importer) => Boolean(importer) } } });
  return (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;
}
const detailCode = await compile('file-detail/FileDetailApp.tsx');
const favoriteCode = await compile('shared/api/favorite-api.ts');
const nodes = tree => Array.isArray(tree) ? tree.flatMap(nodes) : !tree || typeof tree !== 'object' ? []
  : [tree, ...nodes(tree.props?.children)];
const text = tree => Array.isArray(tree) ? tree.map(text).join('') : tree && typeof tree === 'object'
  ? text(tree.props?.children) : typeof tree === 'string' ? tree : '';
const flush = () => new Promise(setImmediate);

function payload(directory = false, hidden = false, path = `root/${hidden ? '.' : ''}${directory ? 'folder' : 'item.txt'}`) {
  return { detail: { path, name: path.split('/').at(-1), parentPath: 'root', directory, hidden },
    urls: { actions: [], parentDirectory: '/files?path=root', download: '/download', downloadZip: '/download-zip' },
    shareDefaults: { previewEnabled: false }, shares: [], favorite: true, transferBuffer: {} };
}

function setup(t, initial = payload()) {
  const h = createHookHarness(), pending = [], posts = [], timeline = [], navigations = [], errors = [];
  h.react.startTransition = callback => callback();
  const url = '/files/detail?' + new URLSearchParams({ path: initial.detail.path });
  const router = createMemoryRouter([{ path: '*', element: null }], { initialEntries: [url] });
  const document = new EventTarget();
  let invalidations = 0;
  document.addEventListener('endervault:favorites-changed', () => { invalidations += 1; timeline.push('favorites'); });
  const window = { location: { href: 'https://nas.test' + url, origin: 'https://nas.test' },
    EnderVault: { askConfirmation: async () => true } };
  const formApi = { postForm: (endpoint, values) => new Promise((resolve, reject) => {
    timeline.push('post'); posts.push({ endpoint, values, resolve, reject });
  }), notify: () => timeline.push('notify'), toastError: reason => errors.push(reason) };
  const modules = {
    react: h.react, 'react/jsx-runtime': jsx,
    'react-router-dom': { Link: 'Link', useLocation: () => router.state.location,
      useNavigate: () => (target, options) => { navigations.push({ target, options }); return router.navigate(target, options); } },
    '../shared/layout/LoadingState': { LoadingState: 'LoadingState' },
    '../files/TransferBufferPanel': { TransferBufferPanel: 'TransferBufferPanel' },
    '../shared/api/share-api': { createShareAndCopy() {} },
    '../shared/browser/usePageContextMenu': { usePageContextMenu() {} },
    '../shared/api/form-api': formApi,
    '../shared/api/snapshot-errors': { canRetainSnapshot: () => true },
    '../shared/browser/BrowserEntries': { icon: () => null },
    '../shared/layout/PageHeader': { PageBreadcrumbs: 'PageBreadcrumbs' },
    '../shared/layout/PageErrorPanel': { PageErrorPanel: 'PageErrorPanel' },
    './FileTools': { FileTools: 'FileTools' },
    './file-detail-app.css': {},
    './file-detail-api': { detailQueryKey, loadFileDetail: (query, signal) => new Promise((resolve, reject) => {
      timeline.push('detail'); pending.push({ path: query.get('path'), signal, resolve, reject });
    }) },
    './form-api': formApi,
    './selected-item-api': { resolveSelectedItems() {} },
  };
  function load(code) {
    const module = { exports: {} };
    vm.runInNewContext(code, { module, exports: module.exports, window, document,
      URL, URLSearchParams, AbortController, CustomEvent, DOMException, require(id) {
        assert.ok(id in modules, `unexpected import ${id}`); return modules[id];
      } });
    return module.exports;
  }
  modules['../shared/api/favorite-api'] = load(favoriteCode);
  const { FileDetailApp } = load(detailCode);
  let tree;
  const render = () => { tree = h.render(FileDetailApp); return tree; };
  const button = label => {
    const result = nodes(tree).find(node => node.type === 'button' && text(node.props.children).trim() === label);
    assert.ok(result, `button ${label}`); return result;
  };
  t.after(() => { h.dispose(); router.dispose(); });
  return { h, initial, pending, posts, timeline, navigations, errors, render, button,
    get invalidations() { return invalidations; },
    find: type => nodes(tree).filter(node => node.type === type),
    async ready() { render(); pending[0].resolve(initial); await flush(); render(); timeline.length = 0; },
    async finishDetail(snapshot) { pending.at(-1).resolve(snapshot); await flush(); render(); },
  };
}

for (const directory of [false, true]) for (const hidden of [false, true]) {
  test(`${directory ? 'directory' : 'file'} ${hidden ? 'Make visible' : 'Hide item'} invalidates Favorites before detail prefetch`, async t => {
    const s = setup(t, payload(directory, hidden));
    await s.ready();
    const mutation = s.button(hidden ? 'Make visible' : 'Hide item').props.onClick();
    assert.equal(s.posts[0].endpoint, '/api/v1/files/detail/hidden');
    assert.equal(s.posts[0].values.path, s.initial.detail.path);
    assert.equal(s.posts[0].values.hidden, String(!hidden));
    assert.equal(s.invalidations, 0, 'pending mutation must not publish an update');
    // Linux may rename the item; Windows may preserve its path when the hidden attribute changes.
    const next = directory ? payload(directory, !hidden, s.initial.detail.path) : payload(directory, !hidden);
    const redirectUrl = '/files/detail?' + new URLSearchParams({ path: next.detail.path });
    s.posts[0].resolve({ redirectUrl });
    await flush();
    assert.equal(s.invalidations, 1);
    assert.deepEqual(s.timeline, ['post', 'favorites', 'notify', 'detail']);
    assert.equal(s.pending.at(-1).path, next.detail.path);
    await s.finishDetail(next);
    await mutation;
    s.render();
    assert.equal(s.navigations[0].target, redirectUrl);
    assert.equal(s.invalidations, 1, 'prefetch/navigation must not republish the mutation');
  });
}

test('successful detail mutation still invalidates Favorites after the originating page unmounts', async t => {
  const s = setup(t);
  await s.ready();
  const mutation = s.button('Hide item').props.onClick();
  s.h.dispose();
  s.posts[0].resolve({ redirectUrl: '/files/detail?path=root%2F.item.txt' });
  await mutation;
  assert.equal(s.invalidations, 1);
  assert.equal(s.pending.length, 1, 'departed page must not prefetch');
  assert.equal(s.navigations.length, 0, 'departed page must not redirect');
});

test('failed visibility mutation neither invalidates Favorites nor navigates or reloads details', async t => {
  const s = setup(t);
  await s.ready();
  const mutation = s.button('Hide item').props.onClick();
  s.posts[0].reject(new Error('Visibility change failed'));
  await mutation;
  s.render();
  assert.equal(s.invalidations, 0);
  assert.equal(s.pending.length, 1);
  assert.equal(s.navigations.length, 0);
  assert.equal(s.errors.length, 1);
  assert.ok(s.button('Hide item'), 'existing details remain usable');
});

test('detail rename uses the same successful mutation invalidation boundary', async t => {
  const s = setup(t);
  await s.ready();
  s.find('input')[0].props.onChange({ target: { value: 'renamed.txt' } });
  s.render();
  const mutation = s.find('form')[0].props.onSubmit({ preventDefault() {} });
  assert.equal(s.posts[0].endpoint, '/api/v1/files/detail/rename');
  assert.equal(s.posts[0].values.newName, 'renamed.txt');
  s.posts[0].resolve({});
  await mutation;
  assert.deepEqual(s.timeline, ['post', 'favorites', 'notify', 'detail']);
  assert.equal(s.invalidations, 1);
});

test('confirmed detail trash invalidates Favorites before navigating to the containing folder', async t => {
  const s = setup(t);
  await s.ready();
  const mutation = s.button('Move to trash').props.onClick();
  await flush();
  assert.equal(s.posts[0].endpoint, '/api/v1/files/detail/trash');
  assert.equal(s.invalidations, 0);
  s.posts[0].resolve({ redirectUrl: '/files?path=root' });
  await mutation;
  assert.equal(s.invalidations, 1);
  assert.deepEqual(s.timeline, ['post', 'favorites', 'notify']);
  assert.equal(s.navigations[0].target, '/files?path=root');
  assert.equal(s.pending.length, 1, 'trash redirect does not prefetch the deleted detail');
});

test('a conflict cancellation response only refreshes Favorites and keeps the unchanged item', async t => {
  const s = setup(t, payload(false, true));
  await s.ready();
  const mutation = s.button('Make visible').props.onClick();
  s.posts[0].resolve({ ok: true, notification: { type: 'warning', message: 'Action canceled.' },
    redirectUrl: '/files/detail?' + new URLSearchParams({ path: s.initial.detail.path }) });
  await flush();
  assert.equal(s.invalidations, 1);
  await s.finishDetail(s.initial);
  await mutation;
  s.render();
  assert.ok(s.button('Make visible'), 'server snapshot remains hidden after cancellation');
  assert.equal(s.posts.length, 1, 'refresh cannot replay the canceled mutation');
});
