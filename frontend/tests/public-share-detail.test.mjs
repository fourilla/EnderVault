import assert from 'node:assert/strict';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import { build } from 'vite';
import * as React from 'react';
import * as jsx from 'react/jsx-runtime';
import { renderToStaticMarkup } from 'react-dom/server';

async function compile(file) {
  const result = await build({ configFile: false, logLevel: 'silent', build: { write: false, minify: false,
    lib: { entry: fileURLToPath(new URL(`../src/public-share/${file}`, import.meta.url)), formats: ['cjs'] },
    rolldownOptions: { external: (_id, importer) => Boolean(importer) } } });
  return (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;
}
const pageCode = await compile('SharedFilePage.tsx');
const textCode = await compile('ReadOnlyTextPreview.tsx');
function load(code, modules, globals = {}) {
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, ...globals, require(id) {
    assert.ok(id in modules, `unexpected dependency: ${id}`);
    return modules[id];
  } });
  return module.exports;
}
const ImageViewer = () => null, SharedComicViewer = () => null, ReadOnlyTextPreview = () => null;
const link = props => React.createElement('a', { href: props.to, className: props.className }, props.children);
const modules = { 'react/jsx-runtime': jsx, 'react-router-dom': { Link: link },
  '../shared/file-tools/ImageViewer': { ImageViewer }, '../shared-file/SharedComicViewer': { SharedComicViewer },
  './ReadOnlyTextPreview': { ReadOnlyTextPreview } };
const detail = { targetType: 'DIRECTORY', view: 'detail', path: 'folder/file.txt', parentPath: 'folder',
  name: '<script>file.txt</script>', mediaType: 'text/plain', sizeLabel: '3 B', modifiedLabel: 'now', extension: 'txt',
  rootUrl: '/s/token', upUrl: '/s/token?path=folder', downloadUrl: '/s/token/download/file.txt?path=folder&item=file.txt',
  toolType: 'text', toolLabel: 'Text Preview', previewEnabled: true, previewContentUrl: '/s/token/preview?item=file.txt',
  comicManifestUrl: '/s/token/comic/manifest?item=book.cbz', text: { loaded: true, content: 'abc', message: '' } };
const nodes = tree => Array.isArray(tree) ? tree.flatMap(nodes) : !tree || typeof tree !== 'object' ? []
  : [tree, ...nodes(tree.props?.children)];
function page(opened = false) {
  const writes = [];
  const View = load(pageCode, { ...modules, react: { useState: () => [opened, value => writes.push(value)] } }).SharedFilePage;
  return { View, writes };
}

test('public detail keeps MIME metadata and native download while its preview starts collapsed', () => {
  const { View, writes } = page();
  const tree = View({ detail });
  const disclosure = nodes(tree).find(node => node.type === 'details');
  assert.equal(disclosure.props.open, undefined);
  assert.equal(nodes(tree).filter(node => typeof node.type === 'function' && node.type !== link).length, 0);
  disclosure.props.onToggle({ currentTarget: { open: false } });
  assert.equal(writes.length, 0);
  disclosure.props.onToggle({ currentTarget: { open: true } });
  assert.deepEqual(writes, [true]);
  const html = renderToStaticMarkup(tree);
  assert.match(html, /&lt;script&gt;file.txt&lt;\/script&gt;/);
  assert.match(html, /<dt>Type<\/dt><dd>text\/plain<\/dd>/);
  assert.match(html, /Back to folder/);
  assert.match(html, /href="\/s\/token\/download\/file.txt\?path=folder&amp;item=file.txt"/);
  assert.doesNotMatch(html, /<form|\/api\/|\/files\/|csrf|draft|Save|Extract/);
});

test('disabled or unsupported preview mounts no viewer even if unexpected content URLs are present', () => {
  for (const candidate of [{ ...detail, previewEnabled: false },
    ...['pdf', 'archive', 'hex'].map(toolType => ({ ...detail, toolType }))]) {
    const tree = page(true).View({ detail: candidate });
    assert.equal(nodes(tree).some(node => node.type === 'details'), false);
    assert.match(renderToStaticMarkup(tree), /Download/);
  }
  assert.doesNotMatch(renderToStaticMarkup(page().View({ detail: { ...detail, targetType: 'FILE' } })), /Back to folder/);
});

test('first opening uses the shared image/comic components and readonly text wrapper with token URLs', () => {
  for (const [toolType, component, prop, value] of [
    ['image', ImageViewer, 'sourceUrl', detail.previewContentUrl],
    ['comic', SharedComicViewer, 'manifestUrl', detail.comicManifestUrl],
    ['text', ReadOnlyTextPreview, 'text', detail.text],
  ]) {
    const tree = page(true).View({ detail: { ...detail, toolType } });
    const preview = nodes(tree).find(node => typeof node.type === 'function' && node.type !== link);
    const viewer = preview.type(preview.props);
    assert.equal(viewer.type, component);
    assert.equal(viewer.props[prop], value);
  }
  for (const toolType of ['video', 'audio']) {
    const tree = page(true).View({ detail: { ...detail, toolType } });
    const preview = nodes(tree).find(node => typeof node.type === 'function' && node.type !== link);
    const media = nodes(preview.type(preview.props)).find(node => node.type === toolType);
    assert.equal(media.props.src, detail.previewContentUrl);
    assert.equal(media.props.preload, 'metadata');
    assert.equal(media.props.controls, true);
  }
});

function textView(root, loadTools) {
  const effects = [], writes = [];
  // Keep the production lazy import boundary while supplying a deterministic deferred module in this VM.
  const lazyImport = /import\((['"])\.\.\/file-tools\/main\1\)/;
  assert.match(textCode, lazyImport);
  const View = load(textCode.replace(lazyImport, 'loadTools()'), { 'react/jsx-runtime': jsx,
    react: { useRef: () => ({ current: root }), useState: () => [false, value => writes.push(value)],
      useLayoutEffect: run => effects.push(run) } }, { loadTools }).ReadOnlyTextPreview;
  return { View, effects, writes };
}
const tick = () => new Promise(setImmediate);

test('readonly React text releases its scoped editor and ignores imports completed after departure', async () => {
  for (const leaveEarly of [false, true]) {
    const root = {}, calls = [];
    let complete;
    const s = textView(root, () => new Promise(resolve => { complete = resolve; }));
    const tree = s.View({ name: 'note.md', extension: 'md', text: detail.text });
    assert.doesNotMatch(renderToStaticMarkup(tree), /<form|name="content"|csrf|draft|Save|Preview<\/button>/);
    const cleanup = s.effects[0]();
    if (leaveEarly) cleanup();
    complete({ initializeReadOnlyTextPreview(node) {
      assert.equal(node, root); calls.push('init'); return () => calls.push('destroy');
    } });
    await tick();
    if (!leaveEarly) cleanup();
    assert.deepEqual(calls, leaveEarly ? [] : ['init', 'destroy']);
  }
});

test('unloaded text displays the bounded backend message and never imports CodeMirror', () => {
  const s = textView(null, () => assert.fail('must not load editor for unavailable text'));
  const tree = s.View({ name: 'large.txt', extension: 'txt', text: { loaded: false, content: '', message: '<limit>' } });
  s.effects[0]();
  assert.match(renderToStaticMarkup(tree), /&lt;limit&gt;/);
  assert.equal(s.writes.length, 0);
});
