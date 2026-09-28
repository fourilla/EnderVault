import test from 'node:test';
import assert from 'node:assert/strict';
import { batchSummary, runBatch } from '../src/public-request/upload-batch.js';

const item = (size, loaded = 0, state = 'queued') => ({ file: { size }, loaded, state });

test('overall progress is byte weighted, retains completed bytes and separates receipt from transfer', () => {
    const items = [item(100, 100, 'complete'), item(900, 450, 'uploading')];
    assert.deepEqual(batchSummary(items), { total: 1000, loaded: 550, percent: 55, complete: 1, failed: 0, canceled: 0 });
    items[1].loaded = 900;
    assert.equal(batchSummary(items).percent, 100);
    assert.equal(batchSummary(items).complete, 1);
});

test('empty files wait for receipt and failed/canceled files are not counted as received', () => {
    assert.equal(batchSummary([item(0)]).percent, 0);
    assert.equal(batchSummary([item(0, 0, 'complete')]).percent, 100);
    const result = batchSummary([item(50, 80, 'failed'), item(50, 0, 'canceled')]);
    assert.equal(result.loaded, 50);
    assert.equal(result.complete, 0);
    assert.equal(result.failed, 1);
    assert.equal(result.canceled, 1);
});

test('batch workers limit concurrency and skip queued files canceled before starting', async () => {
    const items = [item(10), item(20), item(30), item(40)];
    let active = 0, maximum = 0, started = 0;
    await runBatch(items, 2, async entry => {
        entry.state = 'uploading';
        started++; maximum = Math.max(maximum, ++active);
        items[2].state = 'canceled';
        await new Promise(resolve => setTimeout(resolve, 1));
        entry.state = 'complete'; active--;
    });
    assert.equal(maximum, 2);
    assert.equal(started, 3);
    assert.equal(batchSummary(items).canceled, 1);
});
