import assert from 'node:assert/strict';
import test from 'node:test';
import { createHashRestoration, createScrollRestoration } from '../src/shared/browser/scroll-restoration.ts';

test('initial history position is restored once, not on repeated refreshes', () => {
  const restoration = createScrollRestoration(640);
  const calls = [];
  for (let i = 0; i < 4; i++) restoration.restore((top) => calls.push(top));
  assert.deepEqual(calls, [640]);
});

test('refresh completion does not undo scrolling performed while the request was pending', () => {
  const restoration = createScrollRestoration(0);
  let viewport = 0;
  const scroll = (top) => { viewport = top; };
  restoration.restore(scroll);
  viewport = 400;
  // A data-only request starts here; it does not enqueue a navigation.
  viewport = 960;
  restoration.restore(scroll);
  assert.equal(viewport, 960);
});

test('explicit navigation to top and back/forward restoration remain available', () => {
  const restoration = createScrollRestoration(0);
  const calls = [];
  const scroll = (top) => calls.push(top);
  restoration.restore(scroll);
  restoration.request(820);
  restoration.restore(scroll);
  restoration.request(0);
  restoration.restore(scroll);
  restoration.restore(scroll);
  assert.deepEqual(calls, [0, 820, 0]);
});

test('an interrupted load keeps its intent until ready and a newer navigation replaces it', () => {
  const restoration = createScrollRestoration(300);
  restoration.request(740);
  restoration.request(125);
  const calls = [];
  restoration.restore((top) => calls.push(top));
  restoration.restore((top) => calls.push(top));
  assert.deepEqual(calls, [125]);
});

test('hash navigation waits for its target and is not repeated by data refresh', () => {
  const restore = createHashRestoration();
  let ready = false;
  let scrolls = 0;
  const visit = () => {
    if (!ready) return false;
    scrolls++;
    return true;
  };
  restore('visit-A', visit);
  restore('visit-A', visit);
  assert.equal(scrolls, 0);
  ready = true;
  restore('visit-A', visit);
  restore('visit-A', visit);
  assert.equal(scrolls, 1);
});

test('a new visit to the same hash can scroll again, including back navigation', () => {
  const restore = createHashRestoration();
  let scrolls = 0;
  const visit = () => { scrolls++; return true; };
  restore('visit-A', visit);
  restore('no-hash', () => true);
  restore('visit-A', visit);
  restore('visit-B-same-hash', visit);
  restore('visit-B-same-hash', visit);
  assert.equal(scrolls, 3);
});
