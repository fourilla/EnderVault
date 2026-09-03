import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import * as React from 'react';
import * as jsx from 'react/jsx-runtime';
import { renderToStaticMarkup } from 'react-dom/server';

async function compile(file) {
  const result = await build({ configFile: false, logLevel: 'silent',
    build: { write: false, minify: false,
      lib: { entry: fileURLToPath(new URL(`../src/${file}`, import.meta.url)), formats: ['cjs'] },
      rolldownOptions: { external: (_id, importer) => Boolean(importer) } },
  });
  return (Array.isArray(result) ? result[0] : result).output.find((item) => item.type === 'chunk').code;
}

const componentCode = await compile('shared/file-tools/ImageViewer.tsx');
const adminCode = await compile('file-detail/FileTools.tsx');
const publicCode = await compile('shared-file/image.tsx');
const disclosureCode = await compile('shared-file/preview-disclosure.ts');

function load(code, modules, globals = {}) {
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, ...globals, require(id) {
    assert.ok(id in modules, `unexpected dependency: ${id}`);
    return modules[id];
  } });
  return module.exports;
}

const { ImageViewer } = load(componentCode, {
  react: React, 'react/jsx-runtime': jsx,
  '../browser/external-assets': { loadScript() {}, loadStyle() {} },
});

test('shared image component exposes only local viewing controls and escapes file names', () => {
  const markup = renderToStaticMarkup(React.createElement(ImageViewer, {
    sourceUrl: '/s/token/preview?path=images&item=a.jpg', name: '<img onerror=alert(1)>',
  }));
  const actions = [...markup.matchAll(/data-image-action="([^"]+)"/g)].map((match) => match[1]);
  assert.deepEqual(actions, ['zoom-out', 'zoom-in', 'one-to-one', 'reset', 'rotate-left', 'rotate-right',
    'flip-horizontal', 'flip-vertical', 'fullscreen']);
  assert.equal((markup.match(/type="button"/g) || []).length, actions.length);
  assert.match(markup, /alt="&lt;img onerror=alert\(1\)&gt;"/);
  assert.match(markup, /src="\/s\/token\/preview\?path=images&amp;item=a.jpg"/);
  assert.doesNotMatch(markup, /<form|\/api\/|\/files\/|data-text-draft|type="submit"/);
});

test('admin and public entry points render the same ImageViewer component', () => {
  const { FileTools } = load(adminCode, {
    'react/jsx-runtime': jsx, '../shared/file-tools/ImageViewer': { ImageViewer },
    './ArchiveTool': {}, './ComicTool': {}, './TextTool': {},
  });
  const admin = FileTools({ payload: { detail: { name: 'photo.jpg' }, tool: { type: 'image', label: 'Image' },
    urls: { previewContent: '/files/preview?item=photo.jpg' } } });
  const imageElement = React.Children.toArray(admin.props.children).find((child) => child.type === ImageViewer);
  assert.equal(imageElement.props.sourceUrl, '/files/preview?item=photo.jpg');
  assert.equal(imageElement.props.name, 'photo.jpg');

  const sourceUrl = '/s/token/preview?path=nested%20images&item=photo.jpg';
  const container = { querySelector: () => ({ getAttribute: () => sourceUrl, alt: 'photo.jpg' }) };
  let publicElement;
  load(publicCode, {
    'react/jsx-runtime': jsx, '../shared/file-tools/ImageViewer': { ImageViewer },
    './preview-disclosure': { mountWhenPreviewOpened: (_container, mount) => mount() },
    'react-dom/client': { createRoot(root) {
      assert.equal(root, container);
      return { render(element) { publicElement = element; } };
    } },
  }, { document: { getElementById: () => container } });
  assert.equal(publicElement.type, ImageViewer);
  assert.equal(publicElement.props.sourceUrl, sourceUrl);
  assert.equal(publicElement.props.name, 'photo.jpg');
});

test('public bootstrap leaves non-image or missing-source pages alone', () => {
  for (const container of [null, { querySelector: () => null },
    { querySelector: () => ({ getAttribute: () => '', alt: '' }) }]) {
    load(publicCode, { 'react/jsx-runtime': jsx, '../shared/file-tools/ImageViewer': { ImageViewer },
      './preview-disclosure': { mountWhenPreviewOpened: (_container, mount) => mount() },
      'react-dom/client': { createRoot() { assert.fail('must not mount without an image source'); } },
    }, { document: { getElementById: () => container } });
  }
});

test('shared preview enhancement mounts once when its collapsed disclosure is opened', () => {
  const { mountWhenPreviewOpened } = load(disclosureCode, {});
  const disclosure = Object.assign(new EventTarget(), { open: false });
  const container = { closest(selector) {
    assert.equal(selector, 'details[data-shared-preview]');
    return disclosure;
  } };
  let mounts = 0;

  mountWhenPreviewOpened(container, () => { mounts += 1; });
  disclosure.dispatchEvent(new Event('toggle'));
  assert.equal(mounts, 0);
  disclosure.open = true;
  disclosure.dispatchEvent(new Event('toggle'));
  disclosure.open = false;
  disclosure.dispatchEvent(new Event('toggle'));
  disclosure.open = true;
  disclosure.dispatchEvent(new Event('toggle'));
  assert.equal(mounts, 1);
});

test('image enhancement waits for assets, initializes its own root and cleans up on departure', async () => {
  const root = {}, calls = [], effects = [], pending = [];
  load(componentCode, {
    react: { useRef: () => ({ current: root }), useState: () => [false, () => {}],
      useEffect: (run) => effects.push(run) }, 'react/jsx-runtime': jsx,
    '../browser/external-assets': { loadStyle: (url) => calls.push(url),
      loadScript: (url) => new Promise((resolve) => pending.push({ url, resolve })) },
  }, { window: { EnderVaultImageViewers: {
    init: (node) => calls.push(['init', node]), destroy: (node) => calls.push(['destroy', node]),
  } } }).ImageViewer({ sourceUrl: '/s/token/preview', name: 'a.jpg' });
  const cleanup = effects[0]();
  assert.match(pending[0].url, /viewer\.min\.js$/);
  pending[0].resolve();
  await new Promise(setImmediate);
  assert.equal(pending[1].url, '/js/image-viewer.js');
  pending[1].resolve();
  await new Promise(setImmediate);
  assert.deepEqual(calls.at(-1), ['init', root]);
  cleanup();
  assert.deepEqual(calls.at(-1), ['destroy', root]);
});

test('failed asset load needs no admin shell and never updates an unmounted viewer', async () => {
  for (const leave of [false, true]) {
    const states = [], effects = [];
    let reject;
    load(componentCode, {
      react: { useRef: () => ({ current: {} }), useState: () => [false, (value) => states.push(value)],
        useEffect: (run) => effects.push(run) }, 'react/jsx-runtime': jsx,
      '../browser/external-assets': { loadStyle() {}, loadScript: () => new Promise((_resolve, fail) => { reject = fail; }) },
    }, { window: {} }).ImageViewer({ sourceUrl: '/s/token/preview', name: 'a.jpg' });
    const cleanup = effects[0]();
    if (leave) cleanup();
    reject(new Error('offline'));
    await new Promise(setImmediate);
    assert.deepEqual(states, leave ? [false] : [false, true]);
  }
});

test('existing viewer bridge reuses local actions and releases fullscreen and listeners', () => {
  const classes = () => {
    const values = new Set();
    return { add: (...names) => names.forEach((name) => values.add(name)),
      remove: (...names) => names.forEach((name) => values.delete(name)), contains: (name) => values.has(name),
      toggle(name, enabled) { if (enabled ?? !values.has(name)) values.add(name); else values.delete(name); } };
  };
  const names = ['zoom-out', 'zoom-in', 'one-to-one', 'reset', 'rotate-left', 'rotate-right',
    'flip-horizontal', 'flip-vertical', 'fullscreen'];
  const buttons = names.map((name) => Object.assign(new EventTarget(), {
    dataset: { imageAction: name }, classList: classes(), setAttribute() {}, disabled: true,
  }));
  const source = Object.assign(new EventTarget(), { complete: true, naturalWidth: 640, naturalHeight: 480 });
  const dimensions = {}, zoom = {}, calls = [];
  const root = { dataset: {}, classList: classes(), matches: () => true,
    querySelector(selector) {
      return { '[data-image-viewer-source]': source, '[data-image-dimensions]': dimensions,
        '[data-image-zoom]': zoom, "[data-image-action='fullscreen']": buttons.at(-1) }[selector];
    }, querySelectorAll: () => buttons,
  };
  const document = Object.assign(new EventTarget(), { body: { classList: classes() } });
  const window = { setTimeout: (run) => run(), dispatchEvent() {}, Viewer: class {
    constructor(image, options) { assert.equal(image, source); assert.equal(options.inline, true); calls.push('create'); }
    zoom(value) { calls.push(['zoom', value]); } zoomTo(value) { calls.push(['zoomTo', value]); }
    reset() { calls.push('reset'); } rotate(value) { calls.push(['rotate', value]); }
    scaleX(value) { calls.push(['scaleX', value]); } scaleY(value) { calls.push(['scaleY', value]); }
    destroy() { calls.push('destroy'); }
  } };
  vm.runInNewContext(readFileSync(new URL('../../src/main/resources/static/js/image-viewer.js', import.meta.url), 'utf8'),
    { window, document, Event });
  window.EnderVaultImageViewers.init(root);
  window.EnderVaultImageViewers.init(root);
  source.dispatchEvent(new Event('ready'));
  assert.ok(buttons.every((button) => !button.disabled));
  buttons.forEach((button) => button.dispatchEvent(new Event('click')));
  assert.deepEqual(calls, ['create', ['zoom', -0.15], ['zoom', 0.15], ['zoomTo', 1], 'reset',
    ['rotate', -90], ['rotate', 90], ['scaleX', -1], ['scaleY', -1]]);
  assert.equal(document.body.classList.contains('is-image-viewer-fullscreen'), true);
  assert.equal(dimensions.textContent, '640 x 480');
  window.EnderVaultImageViewers.destroy(root);
  assert.equal(document.body.classList.contains('is-image-viewer-fullscreen'), false);
  assert.equal(calls.at(-1), 'destroy');
  const count = calls.length;
  buttons.forEach((button) => button.dispatchEvent(new Event('click')));
  assert.equal(calls.length, count);
});
