import assert from 'node:assert/strict';
import test from 'node:test';
import { mergeOperations, formatBytes, usagePercent, uptimeLabel } from '../src/dashboard/dashboard-model.ts';

test('live server state replaces stale summary without double counting', () => {
  const result = mergeOperations([
    { id: 'server-1', title: 'Copy', active: true, status: 'running', progressPercent: 10 },
  ], [], [
    { id: 'server-1', title: 'Copy', type: 'COPY', status: 'complete', percent: 100 },
    { id: 'upload-1', title: 'Upload', type: 'UPLOAD', status: 'uploading', percent: 30 },
  ]);
  assert.equal(result.length, 2);
  assert.equal(result.filter(item => item.active).length, 1);
  assert.equal(result[0].source, 'upload');
  assert.equal(result[1].status, 'complete');
});

test('canonical remote state wins over its activity mirror', () => {
  const result = mergeOperations([], [
    { id: '1', fileName: 'File', active: false, status: 'COMPLETE', progressPercent: 100 },
  ], [
    { id: 'remote-1', title: 'File', type: 'REMOTE_DOWNLOAD', status: 'running', percent: 90 },
  ]);
  assert.equal(result.length, 1);
  assert.equal(result[0].status, 'complete');
  assert.equal(result[0].active, false);
});

test('active work and failures are prioritized and pending is not running', () => {
  const result = mergeOperations([], [], ['complete', 'pending', 'failed', 'running'].map((status, i) => ({
    id: String(i), status, percent: 0, title: status, type: 'TASK',
  })));
  assert.deepEqual(result.map(item => item.status), ['running', 'failed', 'pending', 'complete']);
  assert.equal(result.filter(item => item.active).length, 1);
});

test('resource formatting distinguishes unavailable from zero', () => {
  assert.equal(formatBytes(null), 'Unavailable');
  assert.equal(formatBytes(0), '0 B');
  assert.equal(formatBytes(1024), '1.0 KB');
  assert.equal(usagePercent(0, 100), 0);
  assert.equal(usagePercent(10, 0), null);
  assert.equal(usagePercent(110, 100), null);
  assert.equal(usagePercent(null, 100), null);
  assert.equal(uptimeLabel(90 * 60_000), '1h 30m');
  assert.equal(uptimeLabel(25 * 3600_000), '1d 1h');
});
