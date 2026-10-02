import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { batchSummary, runBatch } from '../src/public-request/upload-batch.js';

test('request queues preserve intrinsic row heights inside constrained layouts', () => {
    const css = readFileSync(new URL('../../src/main/resources/static/css/pages/file-request-public.css', import.meta.url), 'utf8');
    const queue = css.match(/\.file-request-queue\s*\{([^}]+)\}/)[1];
    assert.match(queue, /grid-auto-rows:\s*max-content/);
    assert.match(queue, /align-content:\s*start/);
    assert.match(queue, /overflow-y:\s*auto/);
});

class Element {
    children = []; events = {}; dataset = {}; hidden = false; disabled = false; textContent = ''; value = ''; scrollTop = 0;
    classList = { toggle() {}, add() {}, remove() {} };
    addEventListener(name, listener) { this.events[name] = listener; }
    async fire(name, event = {}) { return this.events[name]?.({ preventDefault() {}, ...event }); }
    append(...children) { children.forEach(child => { child.parent = this; this.children.push(child); }); }
    replaceChildren(...children) { this.children = []; this.scrollTop = 0; this.append(...children); }
    remove() { this.parent.children = this.parent.children.filter(child => child !== this); }
    setAttribute() {}
    reportValidity() { return true; }
}

function setup() {
    const page = Object.fromEntries(['input', 'pick', 'submit', 'queue', 'status'].map(key => [key, new Element()]));
    page.input.accept = '.txt';
    const modal = Object.fromEntries(['list', 'title', 'summary', 'progress-area', 'progress', 'totals', 'result', 'close', 'done', 'start', 'cancel'].map(key => [key, new Element()]));
    const form = new Element(), dialog = new Element(), window = new Element();
    const name = new Element(); name.value = 'Guest';
    form.dataset = { parallelUploads: '2', admissionUrl: '/r/example/upload-sessions' };
    form.elements = { namedItem: () => name };
    form.querySelector = selector => page[selector.match(/data-file-request-(.+)\]/)[1]];
    dialog.querySelector = selector => modal[selector.match(/data-upload-(.+)\]/)[1]];
    let policy, opened = false;
    const calls = [];
    window.EnderVaultResumableUpload = { create(options) {
        const call = { options }; calls.push(call);
        return { start() {
            options.onState('uploading', 'Uploading');
            return new Promise(resolve => { call.resolve = resolve; });
        }, async abort() { call.resolve({ status: 'CANCELED' }); } };
    } };
    const document = {
        querySelector: selector => selector === '[data-file-request-upload]' ? form : dialog,
        createElement: () => new Element(),
    };
    const source = readFileSync(new URL('../src/public-request/upload.js', import.meta.url), 'utf8').replace(/^import .*;\r?\n/gm, '');
    vm.runInNewContext(source, { window, document, batchSummary, runBatch, mountDialog(_dialog, readPolicy) {
        policy = readPolicy; opened = true; return () => { opened = false; };
    } });
    return { page, modal, form, calls, window, get opened() { return opened; }, get policy() { return policy(); },
        async select(count) {
            page.input.files = Array.from({ length: count }, (_, index) => ({ name: `${index}.txt`, size: 100, lastModified: 1, type: 'text/plain' }));
            await page.input.fire('change');
        } };
}

test('confirmation sends nothing; stable modal rows survive progress; only received items leave the page', async () => {
    const ui = setup(); await ui.select(2);
    await ui.form.fire('submit');
    assert.equal(ui.calls.length, 0);
    assert.equal(ui.opened, true);
    await ui.modal.close.fire('click');
    assert.equal(ui.page.queue.children.length, 2);
    await ui.form.fire('submit');
    const running = ui.modal.start.fire('click');
    assert.equal(ui.policy.busy, true);
    await ui.modal.close.fire('click'); assert.equal(ui.opened, true);
    const row = ui.modal.list.children[0]; ui.modal.list.scrollTop = 120;
    ui.calls[0].options.onProgress(100);
    assert.equal(ui.modal.list.children[0], row);
    assert.equal(ui.modal.list.scrollTop, 120);
    ui.calls[0].resolve({ status: 'RECEIVED' });
    ui.calls[1].resolve({ status: 'FAILED', message: 'Rejected' });
    await running;
    assert.match(ui.modal.result.textContent, /1 received \/ 1 failed/);
    assert.equal(ui.modal.list.children.length, 2);
    await ui.modal.done.fire('click');
    assert.equal(ui.page.queue.children.length, 1);
    assert.equal(ui.page.status.textContent, '');
    await ui.form.fire('submit');
    const retry = ui.modal.start.fire('click');
    assert.equal(ui.calls.length, 3);
    ui.calls[2].resolve({ status: 'RECEIVED' }); await retry;
    await ui.modal.done.fire('click'); assert.equal(ui.page.queue.hidden, true);
});

test('cancel all prevents queued admissions and retains canceled files without claiming success', async () => {
    const ui = setup(); await ui.select(4); await ui.form.fire('submit');
    const running = ui.modal.start.fire('click');
    assert.equal(ui.calls.length, 2);
    let warned = false;
    await ui.window.fire('beforeunload', { preventDefault() { warned = true; } });
    assert.equal(warned, true);
    await ui.modal.cancel.fire('click'); await running;
    assert.equal(ui.calls.length, 2);
    assert.match(ui.modal.result.textContent, /0 received \/ 0 failed \/ 4 canceled/);
    assert.equal(ui.policy.busy, false);
    await ui.modal.done.fire('click');
    assert.equal(ui.page.queue.children.length, 4);
});
