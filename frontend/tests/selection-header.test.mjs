import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import test from 'node:test';
import { build } from 'vite';
import * as jsx from 'react/jsx-runtime';

const result = await build({ configFile: false, logLevel: 'silent', build: {
  write: false, minify: false,
  lib: { entry: fileURLToPath(new URL('../src/shared/browser/SelectionHeader.tsx', import.meta.url)), formats: ['cjs'] },
  rolldownOptions: { external: (_id, importer) => Boolean(importer) },
} });
const code = (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;

test('shared selection header preserves partial/full/empty states and forwards one checked value', () => {
  const ref = { current: {} }, effects = [], calls = [];
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, require: id => id === 'react'
    ? { useRef: () => ref, useEffect: run => effects.push(run) } : jsx });
  const render = (total, selected, disabled = false) => {
    const tree = module.exports.SelectionHeader({ total, selected, disabled,
      label: 'Select this result', onChange: checked => calls.push(checked) });
    effects.splice(0).forEach(run => run());
    assert.equal(tree.props.className, 'select-col');
    assert.equal(tree.props.children.props.className, 'select-all-label');
    return tree.props.children.props.children;
  };
  let input = render(3, 1);
  assert.equal(ref.current.indeterminate, true);
  assert.equal(input.props.checked, false);
  assert.equal(input.props.disabled, false);
  input.props.onChange({ currentTarget: { checked: true } });
  input = render(3, 3);
  assert.equal(ref.current.indeterminate, false);
  assert.equal(input.props.checked, true);
  input.props.onChange({ currentTarget: { checked: false } });
  input = render(0, 0);
  assert.equal(input.props.checked, false);
  assert.equal(input.props.disabled, true);
  assert.equal(ref.current.indeterminate, false);
  assert.equal(render(3, 1, true).props.disabled, true);
  assert.deepEqual(calls, [true, false]);
});

test('Files, Bookmarks and Pending use one header while keeping their domain selection adapters', () => {
  for (const path of ['shared/browser/BrowserEntries.tsx', 'bookmarks/BookmarkEntries.tsx',
    'pending-decisions/PendingDecisionsApp.tsx']) {
    const source = readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8');
    assert.match(source, /import \{ SelectionHeader \} from/);
    assert.match(source, /<SelectionHeader total=/);
    assert.doesNotMatch(source, /function (SelectionHeader|SelectAllCheckbox)\(/);
  }
});
