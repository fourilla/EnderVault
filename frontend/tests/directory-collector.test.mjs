import assert from 'node:assert/strict';
import test from 'node:test';
import { collectDrop, collectFileList, rootSignature, validateRoot } from '../src/app/uploads/collect-uploads.ts';

const file = (path, size = 1) => {
  const value = new File(['x'.repeat(size)], path.split('/').at(-1), { lastModified: 42 });
  Object.defineProperty(value, 'webkitRelativePath', { value: path });
  return value;
};
const entryFile = (name) => ({ name, isFile: true, isDirectory: false,
  file: (resolve) => resolve(file(name)) });
const directory = (name, batches = []) => ({ name, isFile: false, isDirectory: true,
  createReader() { let index = 0; return { readEntries: (resolve) => resolve(batches[index++] || []) }; } });
const drop = (...entries) => ({ files: [], items: entries.map((entry) => ({
  kind: 'file', webkitGetAsEntry: () => entry, getAsFile: () => null,
})) });

test('picker retains distinct roots and root-relative paths, including duplicate basenames', () => {
  const selection = collectFileList([file('one/a/x'), file('one/b/x'), file('two/x')]);
  assert.deepEqual(selection.roots.map((root) => [root.name, root.files.map((item) => item.path)]),
    [['one', ['a/x', 'b/x']], ['two', ['x']]]);
  assert.equal(selection.files.length, 0);
  assert.deepEqual(selection.roots[0].directories, []);
});

test('recursive drops drain every batch until empty and preserve explicit empty directories/roots', async () => {
  const result = await collectDrop(drop(directory('root', [
    [entryFile('one'), directory('nested', [[entryFile('two')]])],
    [directory('empty')], [entryFile('three')],
  ]), directory('empty-root')));
  assert.deepEqual(result.roots[0].files.map((item) => item.path), ['one', 'nested/two', 'three']);
  assert.deepEqual(result.roots[0].directories, ['empty']);
  assert.equal(result.roots[1].name, 'empty-root');
  assert.deepEqual(result.roots[1].files, []);
});

test('fallback FileList groups roots instead of flattening and supports loose files', async () => {
  const values = [file('root/a'), file('root/b')];
  const result = await collectDrop({ files: values, items: values.map((value) => ({
    kind: 'file', getAsFile: () => value,
  })) });
  assert.equal(result.roots.length, 1);
  assert.equal(result.roots[0].files.length, 2);
  assert.equal(collectFileList([new File(['x'], 'loose')]).files.length, 1);
});

test('rejects traversal, duplicate paths, collisions, excessive depth/length/count before admission', async () => {
  for (const path of ['root/../x', 'root//x', 'root/a\\x', 'root/C:x', `root/${'a/'.repeat(65)}x`, `root/${'x'.repeat(1025)}`]) {
    assert.throws(() => collectFileList([file(path)]));
  }
  assert.throws(() => collectFileList([file('root/x'), file('root/x')]));
  assert.throws(() => collectFileList([file('root/a'), file('root/a/x')]));
  assert.throws(() => collectFileList(Array.from({ length: 10000 }, (_, i) => file(`root/${i}`))));
  assert.throws(() => validateRoot({ name: 'root', files: Array.from({ length: 5000 }, (_, i) => ({ path: `${i}/x`, file: file('x') })), directories: [] }));
  await assert.rejects(collectDrop(drop(directory('root', [[directory('..')]]))));
});

test('reader errors fail collection without submitting a partial tree', async () => {
  await assert.rejects(collectDrop(drop({ name: 'root', isDirectory: true,
    createReader: () => ({ readEntries: (_resolve, reject) => reject(new Error('denied')) }) })), /denied/);
});

test('manifest signature is order-independent and changes with destination/path/metadata', () => {
  const root = collectFileList([file('root/a'), file('root/b')]).roots[0];
  const signature = rootSignature(root, 'dest');
  assert.equal(signature, rootSignature({ ...root, files: [...root.files].reverse() }, 'dest'));
  assert.notEqual(signature, rootSignature(root, 'other'));
  assert.notEqual(signature, rootSignature({ ...root, directories: ['empty'] }, 'dest'));
  assert.notEqual(signature, rootSignature(collectFileList([file('root/a', 2)]).roots[0], 'dest'));
});
