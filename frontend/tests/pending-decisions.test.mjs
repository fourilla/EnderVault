import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import { readFileSync } from 'node:fs';

async function compile(path) {
  const result = await build({
    configFile: false,
    logLevel: 'silent',
    build: {
      write: false,
      minify: false,
      lib: { entry: fileURLToPath(new URL(path, import.meta.url)), formats: ['cjs'] },
      rolldownOptions: { external: (_id, importer) => Boolean(importer) },
    },
  });
  const output = (Array.isArray(result) ? result[0] : result).output;
  return output.find((item) => item.type === 'chunk').code;
}

const policyModule = { exports: {} };
vm.runInNewContext(await compile('../src/pending-decisions/pending-decision-actions.ts'), {
  module: policyModule, exports: policyModule.exports,
});
const policy = policyModule.exports;
const code = await compile('../src/pending-decisions/PendingDecisionActions.tsx');

test('row and labelled dialog buttons share action definitions and the same controller', async () => {
  for (const directory of [false, true]) for (const labelled of [false, true]) {
    const calls = [];
    const decision = { id: 'pending-1', directory, originalFilename: 'photos.v1' };
    const jsx = (type, props) => ({ type, props });
    const react = {
      createElement: (type, props, ...children) => jsx(type, { ...props, children }),
    };
    const module = { exports: {} };
    vm.runInNewContext(code, {
      module, exports: module.exports, React: react,
      require: (id) => {
        if (id === 'react') return react;
        if (id === 'react/jsx-runtime') return { jsx, jsxs: jsx, Fragment: 'fragment' };
        if (id.includes('pending-decision-actions')) return policy;
        assert.fail(`Unexpected view dependency: ${id}`);
      },
    });
    const buttons = [];
    const visit = (node) => {
      if (Array.isArray(node)) return node.forEach(visit);
      if (!node || typeof node !== 'object') return;
      if (node.type === 'button') buttons.push(node);
      visit(node.props?.children);
    };
    const actions = { preparing: () => false, canRun: () => true, run: (...args) => calls.push(args) };
    visit(module.exports.PendingDecisionActions({ decision, actions, labelled }));
    const definitions = policy.pendingDecisionActionsFor([decision]);
    assert.deepEqual(buttons.map(button => button.props['aria-label']), Array.from(definitions, action => action.label));
    assert.equal(buttons.some((button) => button.props['aria-label'] === 'Replace existing file'), !directory);
    buttons.forEach((button, index) => {
      assert.equal(button.props.title, labelled ? definitions[index].description : definitions[index].label);
      assert.equal(button.props.disabled, false);
      button.props.onClick();
    });
    assert.deepEqual(calls, Array.from(definitions, action => [decision.id, action.id]));
  }
});

test('single and multiple policy use the same definitions, without parsing labels or bulk rename/merge', () => {
  const file = { id: 'file', directory: false };
  const directory = { id: 'directory', directory: true };
  const owned = { ...directory, mergeId: 'review' };
  const ids = items => Array.from(policy.pendingDecisionActionsFor(items), action => action.id);
  assert.deepEqual(ids([]), []);
  assert.deepEqual(ids([file]), ['KEEP_BOTH', 'SAVE_AS', 'REPLACE', 'DISCARD']);
  assert.deepEqual(ids([directory]), ['MERGE', 'KEEP_BOTH', 'SAVE_AS', 'DISCARD']);
  assert.deepEqual(ids([owned]), ['REVIEW']);
  assert.deepEqual(ids([file, { ...file, id: 'file-2' }]), ['KEEP_BOTH', 'REPLACE', 'DISCARD']);
  assert.deepEqual(ids([file, directory]), ['KEEP_BOTH', 'DISCARD']);
  assert.deepEqual(ids([file, owned]), []);
  assert.deepEqual(ids([owned, { ...owned, id: 'other' }]), []);
});

test('list and Shell dialog share the hook, and no old preparation button or view-owned requests remain', () => {
  for (const path of ['PendingDecisionsApp.tsx', 'PendingDecisionDialog.tsx']) {
    const source = readFileSync(new URL(`../src/pending-decisions/${path}`, import.meta.url), 'utf8');
    assert.match(source, /usePendingDecisionActions/);
    assert.match(source, /PendingDecisionActions decision=\{decision\} actions=\{actions\}/);
  }
  const source = readFileSync(new URL('../src/pending-decisions/PendingDecisionActions.tsx', import.meta.url), 'utf8');
  assert.doesNotMatch(source, /useState|PrepareDirectoryMergeButton|postForm|resolvePendingDecision|askConfirmation/);
});
