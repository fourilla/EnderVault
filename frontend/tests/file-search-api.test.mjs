import assert from 'node:assert/strict';
import test from 'node:test';
import { loadBrowserPayload } from '../src/files/browser-api.ts';

const stateFor = (query) => ({
  version: 1, mode: 'search', path: 'photos/a+b & c', query,
  page: 1, scrollTop: 0,
});

test('Files search preserves phrases, boolean syntax and offset timestamps in its existing q contract', async (t) => {
  const queries = [
    '"summer holiday"', 'name:"summer holiday"',
    'type:file (name:report || path:photos) modified:>=2026-10-03T09:00:00+09:00',
    '"name:a && (b)||c"',
  ];
  const signal = new AbortController().signal;
  let expectedQuery;
  const snapshot = { mode: 'search', entries: [], directories: [] };
  const fetch = t.mock.method(globalThis, 'fetch', async (url, options) => {
    const parsed = new URL(url, 'https://nas.test');
    assert.equal(parsed.pathname, '/api/v1/fs/search');
    assert.equal(parsed.searchParams.get('q'), expectedQuery);
    assert.equal(parsed.searchParams.get('path'), 'photos/a+b & c');
    assert.equal(options.signal, signal);
    assert.equal(options.credentials, 'same-origin');
    assert.equal(options.headers.Accept, 'application/json');
    return { ok: true, json: async () => snapshot };
  });

  for (const query of queries) {
    expectedQuery = query;
    assert.equal(await loadBrowserPayload(stateFor(query), signal), snapshot);
  }
  assert.equal(fetch.mock.callCount(), queries.length);
});

test('invalid search queries expose the server notification without treating an error as a list or retrying', async (t) => {
  const fetch = t.mock.method(globalThis, 'fetch', async () => ({
    ok: false, status: 400,
    json: async () => ({ ok: false, position: 2, notification: { message: 'Unknown search field: typo' } }),
  }));

  await assert.rejects(loadBrowserPayload(stateFor('  typo:report'), new AbortController().signal),
    { message: 'Unknown search field: typo' });
  assert.equal(fetch.mock.callCount(), 1);
});

test('search cancellation retains its identity rather than becoming a search validation failure', async (t) => {
  const controller = new AbortController();
  const failure = new DOMException('Canceled', 'AbortError');
  t.mock.method(globalThis, 'fetch', async (_url, options) => {
    assert.equal(options.signal, controller.signal);
    throw failure;
  });

  await assert.rejects(loadBrowserPayload(stateFor('name:report'), controller.signal),
    (reason) => reason === failure);
});
