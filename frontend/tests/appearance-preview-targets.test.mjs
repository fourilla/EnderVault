import assert from 'node:assert/strict';
import test from 'node:test';
import { registerHooks } from 'node:module';

const hook = registerHooks({ resolve(specifier, context, nextResolve) {
  return nextResolve(specifier === '../shared/appearance/presets' ? `${specifier}.ts` : specifier, context);
} });
const { describePreviewTarget, previewTargetAt } = await import('../src/settings/appearancePreviewTargets.ts');
hook.deregister();

test('explicit color targets distinguish editable, derived and fixed colors', () => {
  assert.deepEqual(describePreviewTarget('buttonText'), { field: 'buttonText', title: 'Button text' });
  assert.equal(describePreviewTarget('selection').field, 'accent');
  for (const target of ['success', 'warning', 'danger', 'fixed', '__proto__']) {
    assert.equal(describePreviewTarget(target).field, undefined);
  }
});

test('border, left status strip, text and background use their declared targets', () => {
  const saved = [globalThis.Element, globalThis.HTMLElement, globalThis.getComputedStyle];
  class Node {
    constructor(dataset, parentElement = null) { this.dataset = dataset; this.parentElement = parentElement; }
    contains(child) { return child === this || Boolean(child?.parentElement && this.contains(child.parentElement)); }
    getBoundingClientRect() { return { left: 0, top: 0, right: 100, bottom: 50 }; }
  }
  globalThis.Element = Node;
  globalThis.HTMLElement = Node;
  globalThis.getComputedStyle = () => ({ borderLeftWidth: '4px', borderTopWidth: '1px', borderRightWidth: '1px', borderBottomWidth: '1px' });
  try {
    const root = new Node({ previewColor: 'background' });
    const toast = new Node({ previewColor: 'panelElevated', previewBorder: 'border', previewLeftBorder: 'warning' }, root);
    const text = new Node({ previewColor: 'text' }, toast);
    assert.equal(previewTargetAt(root, text, 20, 20), 'text');
    assert.equal(previewTargetAt(root, toast, 20, 20), 'panelElevated');
    assert.equal(previewTargetAt(root, toast, 2, 20), 'warning');
    assert.equal(previewTargetAt(root, toast, 99.5, 20), 'border');
    assert.equal(previewTargetAt(root, root, 50, 20), 'background');
    assert.equal(previewTargetAt(root, new Node({ previewColor: 'danger' }), 20, 20), 'background');
  } finally {
    [globalThis.Element, globalThis.HTMLElement, globalThis.getComputedStyle] = saved;
  }
});
