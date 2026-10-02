import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { NOTE_MARGIN, MAX_NOTE_Y, projectNotePosition, pagePlacement, defaultNotePlacement, edgeScrollDelta } from '../../src/main/resources/static/js/sticky-note-placement.js';

test('page placement remains relative to the document, not viewport height or scrolling', () => {
  const note = { x: 500, y: 15000, xRatio: 0.75 };
  const container = { width: 1200, documentTop: 80 };
  assert.deepEqual(projectNotePosition(note, container, 600), { x: 446, y: 15000 });
  assert.deepEqual(projectNotePosition(note, { ...container, top: -920, height: 400 }, 600), { x: 446, y: 15000 });
  assert.deepEqual(note, { x: 500, y: 15000, xRatio: 0.75 });
});

test('narrow-screen projection preserves intent and restores the same placement on wider screens', () => {
  const note = { x: 500, y: 2000, xRatio: 0.75 };
  const wide = projectNotePosition(note, { width: 1200, documentTop: 80 }, 600);
  assert.deepEqual(projectNotePosition(note, { width: 360, documentTop: 100 }, 344), { x: 8, y: 2000 });
  assert.deepEqual(projectNotePosition(note, { width: 1200, documentTop: 80 }, 600), wide);
});

test('legacy display translates viewport coordinates without modifying original metadata', () => {
  const note = { x: 900, y: 480, xRatio: null };
  assert.deepEqual(projectNotePosition(note, { width: 1200, documentTop: 80 }, 600), { x: 592, y: 400 });
  assert.deepEqual(projectNotePosition(note, { width: 360, documentTop: 80 }, 344), { x: 8, y: 400 });
  assert.deepEqual(note, { x: 900, y: 480, xRatio: null });
});

test('explicit placement normalizes horizontal travel and clamps page height safely', () => {
  assert.deepEqual(pagePlacement(348, 25000, 1200, 280), { x: 348, y: 25000, xRatio: 340 / 904 });
  assert.deepEqual(pagePlacement(-50, -50, 1200, 280), { x: NOTE_MARGIN, y: 0, xRatio: 0 });
  assert.deepEqual(pagePlacement(50000, MAX_NOTE_Y + 100, 1200, 280), { x: 912, y: MAX_NOTE_Y, xRatio: 1 });
  assert.deepEqual(pagePlacement(8, 80, 360, 344, 0.75), { x: 8, y: 80, xRatio: 0.75 });
});

test('edge scrolling is bounded and stops in the middle of the visible content', () => {
  assert.equal(edgeScrollDelta(400, 80, 900, 16), 0);
  assert.ok(edgeScrollDelta(85, 80, 900, 16) < 0);
  assert.ok(edgeScrollDelta(890, 80, 900, 16) > 0);
  assert.equal(edgeScrollDelta(920, 80, 900, 10000), 19.2);
});

test('all twenty default creation offsets stay visible without changing existing placements', () => {
  const container = { width: 1200, top: -920 };
  const viewport = { top: 80, bottom: 400 };
  const size = { width: 280, height: 220 };
  for (let count = 0; count < 20; count++) {
    const position = defaultNotePlacement(container, viewport, size, count * 24);
    assert.ok(container.top + position.y >= viewport.top + NOTE_MARGIN);
    assert.ok(container.top + position.y + size.height <= viewport.bottom - NOTE_MARGIN);
    assert.ok(position.x >= NOTE_MARGIN && position.x + size.width <= container.width - NOTE_MARGIN);
  }
  assert.deepEqual(container, { width: 1200, top: -920 });
});

test('short viewports place the new note header near the visible content start', () => {
  const position = defaultNotePlacement({ width: 360, top: -920 }, { top: 80, bottom: 180 }, { width: 280, height: 220 }, 456);
  assert.equal(position.y, 1008);
  assert.equal(position.x, 72);
});

test('shell owns the page host and only the temporary input shield is viewport-fixed', () => {
  const css = readFileSync(new URL('../../src/main/resources/static/css/components/sticky-notes.css', import.meta.url), 'utf8');
  const shell = readFileSync(new URL('../src/app/AppShell.tsx', import.meta.url), 'utf8');
  const assets = readFileSync(new URL('../../src/main/resources/templates/fragments/assets.html', import.meta.url), 'utf8');
  assert.match(shell, /<div ref=\{stickyNoteHost\} className="sticky-note-layer" data-sticky-note-layer/);
  assert.match(shell, /const host = stickyNoteHost.current;[\s\S]*spa-shell-disposed.*detail: \{ host \}/);
  assert.match(css, /\.sticky-note-layer\s*\{\s*position: absolute/);
  assert.match(css, /\.sticky-note-card\s*\{\s*position: absolute/);
  assert.match(css, /\.sticky-note-layer.is-interacting::before\s*\{[^}]*position: fixed;[^}]*pointer-events: auto/);
  assert.match(css, /min-height: var\(--sticky-note-page-height, 0px\)/);
  assert.match(assets, /<script type="module" th:src="@\{\/js\/sticky-notes\.js\}"/);
  assert.doesNotMatch(css, /100vh|100vw/);
});
