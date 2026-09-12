import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';

async function load(entry, globals, imports = {}) {
  const result = await build({ configFile: false, logLevel: 'silent', build: {
    write: false, minify: false,
    lib: { entry: fileURLToPath(new URL(entry, import.meta.url)), formats: ['cjs'] },
    rolldownOptions: { external: (_id, importer) => Boolean(importer) },
  } });
  const code = (Array.isArray(result) ? result[0] : result).output.find((item) => item.type === 'chunk').code;
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports,
    require: (id) => imports[id] || Object.entries(imports).find(([key]) => id.includes(key))?.[1] || {}, ...globals });
  return module.exports;
}

test('directory merge choices preserve pending discard and type-conflict restrictions', async () => {
  const api = await load('../src/directory-merges/merge-api.ts', {});
  assert.equal(api.mergeChoices('COPY', 'FILE_CONFLICT').join(','), 'OVERWRITE,KEEP_BOTH,SKIP');
  assert.equal(api.mergeChoices('MOVE', 'TYPE_CONFLICT').join(','), 'KEEP_BOTH,SKIP');
  assert.equal(api.mergeChoices('PENDING', 'TYPE_CONFLICT').join(','), 'KEEP_BOTH,DISCARD_UPLOAD');
});

test('automatic transfer reviews only use copy and move task references', async () => {
  const host = await load('../src/directory-merges/TransferMergeDialogHost.tsx', {}, {
    react: { lazy: () => () => null },
  });
  assert.equal(host.transferMergeReference({ type: 'FILE_COPY', resultReference: 'review' }), 'review');
  assert.equal(host.transferMergeReference({ type: 'FILE_MOVE', resultReference: 'review' }), 'review');
  assert.equal(host.transferMergeReference({ type: 'DIRECTORY_MERGE', resultReference: 'review' }), null);
  assert.equal(host.transferMergeReference({ type: 'FILE_COPY', resultReference: null }), null);
  assert.equal(host.transferMergeReference({ type: 'FILE_TRASH', resultReference: 'other' }), null);
});

test('saving merge choices sends CSRF and revision but never source snapshots', async () => {
  let sent;
  const api = await load('../src/directory-merges/merge-api.ts', {
    window: { EnderVault: { csrfPair: () => ({ value: 'token' }), requestJson: async (...args) => { sent = args; } } },
    document: { querySelector: () => ({ content: 'X-CSRF-CUSTOM' }) },
  });
  await api.saveMergeChoices('plan', 7, { item: 'SKIP' });
  assert.equal(sent[0], '/api/v1/files/directory-merges/plan/choices');
  assert.equal(sent[1].headers['X-CSRF-CUSTOM'], 'token');
  assert.deepEqual(JSON.parse(sent[1].body), { revision: 7, choices: { item: 'SKIP' } });
});

test('closing a merge dialog only dismisses it and never resolves or executes', async () => {
  let closed = 0;
  let mutations = 0;
  const jsx = (type, props) => ({ type, props });
  const react = { useEffect: () => {}, useState: (value) => [value, () => {}],
    createElement: (type, props, ...children) => jsx(type, { ...props, children }) };
  const component = await load('../src/directory-merges/DirectoryMergeDialog.tsx', { React: react }, {
    react, 'react/jsx-runtime': { jsx, jsxs: jsx, Fragment: 'fragment' },
    AppDialog: { AppDialog: 'dialog' },
    'merge-api': { runMerge: () => mutations++, saveMergeChoices: () => mutations++ },
  });
  const tree = component.DirectoryMergeDialog({ id: 'plan', close: () => closed++, changed: () => {} });
  tree.props.onDismiss();
  const visit = (node) => {
    if (Array.isArray(node)) return node.forEach(visit);
    if (!node || typeof node !== 'object') return;
    if (node.type === 'button' && node.props['aria-label'] === 'Close') node.props.onClick();
    visit(node.props?.children);
  };
  visit(tree);
  assert.equal(closed, 2);
  assert.equal(mutations, 0);
});
