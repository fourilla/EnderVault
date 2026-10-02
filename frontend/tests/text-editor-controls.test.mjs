import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';

test('React leaves mutable text editor form values under editor ownership', () => {
  const source = readFileSync(new URL('../src/file-detail/TextTool.tsx', import.meta.url), 'utf8');
  for (const name of ['editorToken', 'draftId', 'forceOverwrite']) {
    const input = source.match(new RegExp(`<input\\b[^>]*name="${name}"[^>]*>`))?.[0];
    assert.ok(input, name);
    // Hidden inputs reflect defaultValue into value as well, so neither prop is safe here.
    assert.doesNotMatch(input, /\b(?:value|defaultValue)=/);
  }
  const editor = readFileSync(new URL('../../src/main/resources/static/js/file-tools.js', import.meta.url), 'utf8');
  assert.match(editor, /editorTokenInput\.value = editorToken/);
  assert.match(editor, /draftIdInput\.value = draftId \|\| ""/);
  assert.match(editor, /forceOverwriteInput\.value = "false"/);
  assert.match(editor, /forceOverwriteInput\.value = "true"/);
});

test('editor selectors inherit UI field sizing rather than fixed toolbar typography', () => {
  const css = readFileSync(new URL('../../src/main/resources/static/css/components/file-tool-toolbar.css', import.meta.url), 'utf8');
  const control = css.match(/\.file-tool-toolbar input\[type="number"\]\s*\{([^}]+)\}/)[1];
  for (const token of ['--control-height', '--field-padding', '--field-font-size', '--field-line-height']) {
    assert.ok(control.includes(`var(${token})`), token);
  }
  assert.doesNotMatch(control, /font:\s*inherit|height:\s*34px/);
});

test('editor selectors do not inherit full-width form layout and wrap trailing actions prematurely', () => {
  const css = readFileSync(new URL('../../src/main/resources/static/css/pages/detail.css', import.meta.url), 'utf8');
  const control = css.match(/\.editor-control\s*\{([^}]+)\}/)[1];
  assert.match(control, /width: auto;/);
  assert.match(control, /flex: 0 0 auto;/);
});

test('file tool spacing scales locally while keeping medium gaps unchanged', () => {
  const css = readFileSync(new URL('../../src/main/resources/static/css/components/file-tool-toolbar.css', import.meta.url), 'utf8');
  const tokens = JSON.parse(readFileSync(new URL('../../src/main/resources/appearance-tokens.json', import.meta.url), 'utf8'));
  const heights = Object.values(tokens.controls).map((size) => parseFloat(size['--control-height']));
  assert.deepEqual(heights.map((h) => Math.min((h - 22) / 2, (h - 6) / 4)), [4, 6, 8, 9, 10]);
  assert.deepEqual(heights.map((h) => (h - 14) / 2), [8, 10, 12, 14, 16]);
  assert.match(css, /\.file-tool-viewer\s*\{[^}]*--file-tool-control-gap: min\(calc\(\(var\(--control-height\) - 22px\) \/ 2\), calc\(\(var\(--control-height\) - 6px\) \/ 4\)\)/);
  assert.match(css, /--file-tool-section-gap: calc\(\(var\(--control-height\) - 14px\) \/ 2\)/);
  assert.match(css, /gap: var\(--file-tool-control-gap\)/);
  assert.match(css, /gap: var\(--file-tool-section-gap\)/);
});

test('text, image and comic viewers opt into the same toolbar without changing global actions', () => {
  for (const path of ['file-detail/TextTool.tsx', 'shared/file-tools/ImageViewer.tsx', 'shared/file-tools/ComicViewer.tsx']) {
    const source = readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8');
    assert.match(source, /file-tool-viewer/);
    assert.match(source, /file-tool-toolbar/);
    assert.match(source, /file-tool-control-group/);
  }
  const css = readFileSync(new URL('../../src/main/resources/static/css/components/file-tool-toolbar.css', import.meta.url), 'utf8');
  const icons = css.match(/\.file-tool-toolbar \.icon-button\s*\{([^}]+)\}/)[1];
  assert.match(icons, /height: var\(--control-height\)/);
  assert.match(icons, /width: var\(--control-height\)/);
});
