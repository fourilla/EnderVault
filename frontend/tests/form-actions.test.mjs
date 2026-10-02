import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');
const cssRoot = '../../src/main/resources/static/css/';

test('shared form actions align and wrap without changing button sizes or outer spacing', () => {
    const css = read(`${cssRoot}components/form-actions.css`);
    const base = css.match(/\.form-actions\s*\{([^}]+)\}/)[1];
    assert.match(base, /display:\s*flex/);
    assert.match(base, /align-items:\s*center/);
    assert.match(base, /flex-wrap:\s*wrap/);
    assert.match(base, /gap:\s*var\(--space-md\)/);
    assert.doesNotMatch(base, /(?:margin|padding|height|width)\s*:/);
    assert.match(css, /\.form-actions\.end\s*\{\s*justify-content:\s*flex-end/);
    assert.match(read(`${cssRoot}app.css`), /components\/form-actions\.css/);
    assert.match(read('../src/styles/main.ts'), /css\/app\.css/);
});

test('request actions reuse shared alignment and input dialogs retain equal columns', () => {
    assert.match(read('../src/file-requests/FileRequestDetailApp.tsx'), /className="form-actions end file-request-detail-actions"/);
    assert.doesNotMatch(read(`${cssRoot}pages/file-requests.css`), /\.form-actions|\.file-request-detail-actions form|\.file-request-delete-panel/);
    assert.doesNotMatch(read(`${cssRoot}pages/file-request-public.css`), /\.file-request-upload-actions/);
    assert.match(read(`${cssRoot}components/controls.css`), /\.text-input-actions\s*\{[^}]*grid-template-columns:\s*1fr 1fr/);
});
