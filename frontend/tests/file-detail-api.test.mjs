import assert from 'node:assert/strict';
import test from 'node:test';
import { detailQueryKey, loadFileDetail } from '../src/file-detail/file-detail-api.ts';

test('detail prefetch identity distinguishes the resource and comic page, not query encoding', () => {
  assert.equal(detailQueryKey(new URLSearchParams('path=a+b.txt')),
    detailQueryKey(new URLSearchParams('path=a%20b.txt')));
  assert.notEqual(detailQueryKey(new URLSearchParams('path=a.txt')),
    detailQueryKey(new URLSearchParams('path=b.txt')));
  assert.notEqual(detailQueryKey(new URLSearchParams('path=a.cbz&comicPage=1')),
    detailQueryKey(new URLSearchParams('path=a.cbz&comicPage=2')));
});

test('detail prefetch uses the shared client, preserves path encoding, and passes cancellation', async (t) => {
  const previousWindow = globalThis.window;
  t.after(() => {
    if (previousWindow === undefined) delete globalThis.window;
    else globalThis.window = previousWindow;
  });
  const snapshot = { detail: { path: 'notes/a+b & c.txt' } };
  const signal = new AbortController().signal;
  globalThis.window = { EnderVault: { requestJson: async (url, options) => {
    const parsed = new URL(url, 'https://nas.test');
    assert.equal(parsed.pathname, '/api/v1/fs/detail');
    assert.equal(parsed.searchParams.get('path'), snapshot.detail.path);
    assert.equal(parsed.searchParams.get('comicPage'), '2');
    assert.equal(parsed.searchParams.has('unused'), false);
    assert.equal(options.signal, signal);
    return snapshot;
  } } };
  assert.equal(await loadFileDetail(new URLSearchParams({
    path: snapshot.detail.path, comicPage: '2', unused: 'ignored',
  }), signal), snapshot);
});
