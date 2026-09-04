import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { build, normalizePath } from 'vite';
import { observeActivityStarts } from '../src/app/activity-starts.ts';

const script = (name) => readFileSync(new URL(`../../src/main/resources/static/js/${name}.js`, import.meta.url), 'utf8');

async function loadTsx(file, window, document, require, extra = '') {
  const entry = fileURLToPath(new URL(`../src/${file}`, import.meta.url));
  const result = await build({
    configFile: false, logLevel: 'silent',
    plugins: [{ name: 'test-private-class', transform(code, id) {
      return id === normalizePath(entry) ? code + '\n' + extra : null;
    } }],
    build: { write: false, minify: false, lib: { entry, formats: ['cjs'] },
      rolldownOptions: { external: (_id, importer) => Boolean(importer) } },
  });
  const output = (Array.isArray(result) ? result[0] : result).output;
  const module = { exports: {} };
  vm.runInNewContext(output.find((item) => item.type === 'chunk').code,
    { module, exports: module.exports, window, document, require });
  return module.exports;
}

function setup(t) {
  const old = { window: globalThis.window, document: globalThis.document };
  const document = new EventTarget();
  const window = new EventTarget();
  const frames = new Map(), timers = new Map(), storage = new Map();
  let sequence = 0, activeId = null, shows = 0, blocked = false;
  let response = [];
  document.visibilityState = 'visible';
  document.fullscreenElement = null;
  document.body = { dataset: { serverTaskActivity: 'true' } };
  document.querySelector = (selector) => selector.startsWith('dialog[') && blocked ? {} : null;
  window.requestAnimationFrame = (callback) => { frames.set(++sequence, callback); return sequence; };
  window.cancelAnimationFrame = (id) => frames.delete(id);
  window.setTimeout = window.setInterval = (callback) => { timers.set(++sequence, callback); return sequence; };
  window.clearTimeout = window.clearInterval = (id) => timers.delete(id);
  window.sessionStorage = { getItem: (key) => storage.get(key), setItem: (key, value) => storage.set(key, value) };
  window.EnderVault = { requestJson: async () => response, csrfPair: () => null };
  const sandbox = { window, document, CustomEvent, FormData, URLSearchParams };
  vm.runInNewContext(script('activity-panel'), sandbox);
  vm.runInNewContext(script('server-tasks'), sandbox);
  globalThis.window = window;
  globalThis.document = document;
  const dispose = observeActivityStarts({ activeId: () => activeId, show: () => { activeId = 'activity'; shows++; } });
  t.after(() => {
    dispose();
    for (const [key, value] of Object.entries(old)) {
      if (value === undefined) delete globalThis[key];
      else globalThis[key] = value;
    }
  });
  const events = [];
  document.addEventListener('endervault:activity-started', (event) => events.push([...event.detail.ids]));
  return {
    window, document, activity: window.EnderVaultActivity, tasks: window.EnderVaultServerTasks,
    frames, events, dispose, shows: () => shows,
    active: (id) => { activeId = id; }, block: (value) => { blocked = value; },
    respond: (value) => { response = value; },
    paint() { const callbacks = [...frames.values()]; frames.clear(); callbacks.forEach((callback) => callback()); },
    start(id) { window.EnderVaultActivity.upsert({ id }); window.EnderVaultActivity.announceStarted([id]); },
  };
}

test('snapshots and progress are silent; explicit batch starts open once without focus or scrolling', (t) => {
  const state = setup(t);
  const activity = state.activity;
  activity.upsert({ id: 'restored', percent: 10 });
  activity.upsert({ id: 'restored', percent: 20 });
  assert.equal(state.frames.size, 0);
  activity.upsert({ id: 'upload-1' });
  activity.upsert({ id: 'upload-2' });
  activity.announceStarted(['upload-1', 'upload-2', 'upload-1']);
  assert.deepEqual(state.events, [['upload-1', 'upload-2']]);
  assert.equal(state.shows(), 0);
  state.paint();
  assert.equal(state.shows(), 1);
  state.active(null);
  activity.upsert({ id: 'upload-1', percent: 80 });
  activity.upsert({ id: 'upload-1', status: 'complete' });
  activity.announceStarted(['upload-1', 'upload-2']);
  state.paint();
  assert.equal(state.shows(), 1);
  state.start('upload-3');
  state.paint();
  assert.equal(state.shows(), 2);
});

test('server track opts in only for submitted tasks, never restored scans, polls or cancellation', async (t) => {
  const state = setup(t);
  const task = { id: 'scan', active: true, status: 'RUNNING', type: 'METADATA_INSPECTION' };
  state.tasks.track(task);
  state.respond([task]);
  await state.tasks.refresh();
  state.paint();
  assert.equal(state.shows(), 0);
  state.tasks.track(task, { announceStart: true });
  state.paint();
  assert.equal(state.shows(), 1);
  state.active(null);
  await state.tasks.refresh();
  state.respond({ task: { ...task, cancelRequested: true } });
  await state.activity.cancel('server-scan');
  state.tasks.track(task, { announceStart: true });
  state.paint();
  assert.equal(state.shows(), 1);
});

test('disabled server task display does not announce or open an empty popover', (t) => {
  const state = setup(t);
  state.document.body.dataset.serverTaskActivity = 'false';
  state.tasks.track({ id: 'hidden', status: 'RUNNING', active: true }, { announceStart: true });
  state.activity.announceStarted(['missing']);
  state.paint();
  assert.equal(state.events.length, 0);
  assert.equal(state.shows(), 0);
});

test('the upload manager announces one batch after queue rows exist, not when queued workers start', async (t) => {
  const state = setup(t);
  const { TestUploadManager } = await loadTsx('app/uploads/UploadManagerContext.tsx', state.window, state.document,
    () => ({ createContext: () => ({}) }), 'export { AdminUploadManager as TestUploadManager };');
  const manager = new TestUploadManager(1, () => {});
  manager.send = () => new Promise(() => {});
  manager.startFiles([{ name: 'one.txt', size: 10 }, { name: 'two.txt', size: 10 }], 'target');
  assert.deepEqual(state.events, [['upload-1', 'upload-2']]);
  assert.equal(state.activity.snapshot().items.length, 2);
  state.paint();
  state.active(null);
  manager.setMaxConcurrentUploads(2);
  manager.startFiles([], 'target');
  state.paint();
  assert.equal(state.shows(), 1);
  assert.equal(state.events.length, 1);
  manager.startFiles([{ name: 'three.txt', size: 10 }], 'other');
  assert.deepEqual(state.events[1], ['upload-3']);
});

test('remote starts publish immediately; an older in-flight snapshot cannot erase them or re-announce', async (t) => {
  const state = setup(t);
  let resolveRequest;
  const react = {
    createContext: () => ({ Provider: 'provider' }), useRef: (current) => ({ current }),
    useState: (value) => [value, () => {}], useCallback: (value) => value,
    useMemo: (factory) => factory(), useEffect() {},
  };
  const { RemoteDownloadTasksProvider } = await loadTsx('app/remote-downloads/RemoteDownloadTasksContext.tsx',
    state.window, state.document, (id) => {
      if (id === 'react') return react;
      if (id === 'react/jsx-runtime') return { jsx: (type, props) => ({ type, props }) };
      if (id === 'react-router-dom') return { useLocation: () => ({ pathname: '/admin/utils/remote-download' }) };
      if (id.endsWith('AdminAppContext')) return { useAdminApp: () => ({ bootstrap: {
        capabilities: { remoteDownloads: true }, tasks: { activityPanelEnabled: true },
      } }) };
      if (id.endsWith('remote-download-api')) return { loadRemoteDownloadTasks: () => new Promise((resolve) => { resolveRequest = resolve; }) };
      throw Error(`Unexpected import: ${id}`);
    });
  const provider = RemoteDownloadTasksProvider({}).props.value;
  const pending = provider.refresh();
  const task = { id: 'new-download', status: 'QUEUED', active: true, fileName: 'file.zip' };
  provider.trackStarted(task);
  assert.equal(state.activity.snapshot().items[0].id, 'remote-new-download');
  resolveRequest([]);
  await pending;
  assert.equal(state.activity.snapshot().items[0].id, 'remote-new-download');
  state.paint();
  assert.equal(state.shows(), 1);
  state.active(null);
  const next = provider.refresh();
  resolveRequest([{ ...task, progressPercent: 40 }]);
  await next;
  state.paint();
  assert.equal(state.shows(), 1);
  assert.equal(state.activity.snapshot().items[0].percent, 40);
});

test('active menus, modals, fullscreen and background tabs take precedence with no delayed reopening', (t) => {
  const state = setup(t);
  const blockers = [
    [() => state.active('account'), () => state.active(null)],
    [() => state.block(true), () => state.block(false)],
    [() => { state.document.fullscreenElement = {}; }, () => { state.document.fullscreenElement = null; }],
    [() => { state.document.visibilityState = 'hidden'; }, () => { state.document.visibilityState = 'visible'; }],
  ];
  blockers.forEach(([block, unblock], index) => {
    block();
    state.start(`blocked-${index}`);
    state.paint();
    unblock();
    state.activity.upsert({ id: `blocked-${index}`, percent: 10 });
    state.activity.announceStarted([`blocked-${index}`]);
    state.paint();
  });
  assert.equal(state.shows(), 0);
});

test('the initiating dialog may close before paint; a new dialog or other popover still blocks', (t) => {
  const state = setup(t);
  state.block(true);
  state.start('remote-1');
  state.block(false);
  state.paint();
  assert.equal(state.shows(), 1);
  state.active(null);
  state.start('remote-2');
  state.block(true);
  state.paint();
  assert.equal(state.shows(), 1);
  state.block(false);
  state.start('remote-3');
  state.active('applications');
  state.paint();
  assert.equal(state.shows(), 1);
});

test('user input, leaving the window, or hiding the tab cancels a scheduled auto-open', (t) => {
  const state = setup(t);
  for (const [target, event] of [[state.document, 'pointerdown'], [state.document, 'keydown'],
    [state.window, 'blur'], [state.document, 'visibilitychange']]) {
    state.start(event);
    if (event === 'visibilitychange') state.document.visibilityState = 'hidden';
    target.dispatchEvent(new Event(event));
    state.document.visibilityState = 'visible';
    state.paint();
  }
  assert.equal(state.shows(), 0);
});

test('simultaneous start signals coalesce and vanished items do not show an empty list', (t) => {
  const state = setup(t);
  state.start('one');
  state.start('two');
  assert.equal(state.frames.size, 1);
  state.paint();
  assert.equal(state.shows(), 1);
  state.active(null);
  state.start('removed');
  state.activity.remove('removed');
  state.paint();
  assert.equal(state.shows(), 1);
});

test('cleanup cancels pending frames and detaches all observers during shell unmount', (t) => {
  const state = setup(t);
  state.start('one');
  state.dispose();
  state.start('two');
  assert.equal(state.frames.size, 0);
  state.paint();
  assert.equal(state.shows(), 0);
});
