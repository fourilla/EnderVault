import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';

const realRequire = createRequire(import.meta.url);
async function component(path, name, props) {
  const result = await build({ configFile: false, logLevel: 'silent', build: {
    write: false, minify: false,
    lib: { entry: fileURLToPath(new URL(`../src/${path}`, import.meta.url)), formats: ['cjs'] },
    rolldownOptions: { external: (_id, importer) => Boolean(importer) },
  } });
  const code = (Array.isArray(result) ? result[0] : result).output.find((item) => item.type === 'chunk').code;
  let index = 0;
  const values = [];
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, window: {}, require: (id) => {
    if (id === 'react') return {
      useId: () => 'test-title',
      useState: (initial) => {
        const position = index++;
        if (!(position in values)) values[position] = initial;
        return [values[position], (value) => { values[position] = typeof value === 'function' ? value(values[position]) : value; }];
      },
    };
    if (id.includes('AppDialog')) return { AppDialog: 'dialog' };
    if (id.includes('BrowserEntries')) return { icon: () => null };
    return realRequire(id);
  } });
  return () => { index = 0; return module.exports[name](props); };
}
function nodes(element) {
  if (!element || typeof element !== 'object') return [];
  return [element, ...[element.props?.children].flat(Infinity).flatMap(nodes)];
}
const find = (render, predicate) => nodes(render()).find(predicate);
const submit = (render) => find(render, (node) => node.type === 'form').props.onSubmit({ preventDefault() {} });

test('view options edit/cancel does not apply; reopen uses current values and Apply commits once', async () => {
  const applied = [];
  const props = { value: { sort: 'name', direction: 'asc', hidden: 'hide', pageSize: 200 },
    sorts: [{ value: 'name', label: 'Name' }, { value: 'size', label: 'Size' }], pageSizes: [50, 200],
    apply: (value) => applied.push(value), reset: async () => {} };
  const render = await component('shared/browser/ViewOptionsControl.tsx', 'ViewOptionsControl', props);
  const open = () => find(render, (node) => node.props?.title === 'View options').props.onClick();
  open();
  find(render, (node) => node.type === 'select' && node.props.value === 'name').props.onChange({ target: { value: 'size' } });
  assert.equal(applied.length, 0);
  find(render, (node) => node.type === 'dialog').props.onDismiss();
  open();
  assert.ok(find(render, (node) => node.type === 'select' && node.props.value === 'name'));
  await submit(render);
  assert.equal(applied.length, 1);
  assert.equal(applied[0].sort, 'name');
});

test('reset remains local until Apply and cancellation discards it', async () => {
  let resets = 0;
  const render = await component('shared/browser/ViewOptionsControl.tsx', 'ViewOptionsControl', {
    value: { sort: 'name', direction: 'asc', hidden: 'hide', pageSize: 200 }, sorts: [], pageSizes: [200],
    apply: () => assert.fail('must reset instead'), reset: async () => { resets++; },
  });
  const open = () => find(render, (node) => node.props?.title === 'View options').props.onClick();
  const reset = () => find(render, (node) => node.type === 'button' && nodes(node).some((child) => child.props?.children === 'Reset view options')).props.onClick();
  open(); reset();
  assert.equal(resets, 0);
  find(render, (node) => node.type === 'dialog').props.onDismiss();
  open(); reset();
  await submit(render);
  assert.equal(resets, 1);
});

test('create dialog rejects an empty name without preventing cancellation, and submits selected kind', async () => {
  let closed = 0;
  const created = [];
  const render = await component('files/CreateItemDialog.tsx', 'CreateItemDialog', {
    close: () => { closed++; }, create: async (...args) => { created.push(args); },
  });
  await submit(render);
  assert.equal(created.length, 0);
  find(render, (node) => node.type === 'dialog').props.onDismiss();
  assert.equal(closed, 1);
  find(render, (node) => node.type === 'button' && nodes(node).some((child) => child.props?.children === 'Directory')).props.onClick();
  find(render, (node) => node.type === 'input').props.onChange({ target: { value: ' folder ' } });
  await submit(render);
  assert.deepEqual(created, [[true, 'folder']]);
});
