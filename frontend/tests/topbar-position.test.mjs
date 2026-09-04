import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

const source = readFileSync(new URL('../../src/main/resources/static/js/topbar-controls.js', import.meta.url), 'utf8');

function setup() {
  const document = new EventTarget(), window = new EventTarget(), control = new EventTarget();
  let rect = { left: 940, width: 42, bottom: 58 }, width = 430;
  const popover = {
    style: { setProperty(name, value) { this[name] = value; } },
    classList: { add() {} }, getBoundingClientRect: () => ({ width }),
  };
  const trigger = { getBoundingClientRect: () => rect };
  control.querySelector = (selector) => selector.endsWith('trigger') ? trigger : popover;
  control.matches = (selector) => selector.includes('.is-open');
  control.contains = () => false;
  document.querySelectorAll = () => [control];
  document.readyState = 'complete';
  window.innerWidth = 1000;
  window.innerHeight = 700;
  vm.runInNewContext(source, { document, window });
  return { window, document, control, popover,
    rect: (value) => { rect = value; }, width: (value) => { width = value; } };
}

test('auto-open uses the same positioning API as hover and clamps within the viewport', () => {
  const state = setup();
  assert.equal(state.popover.style.left, '562px');
  assert.equal(state.popover.style.top, '58px');
  assert.equal(state.popover.style['--topbar-popover-available-height'], '634px');
  state.rect({ left: 400, width: 42, bottom: 58 });
  state.window.EnderVaultTopbarControls.positionPopover(state.control);
  assert.equal(state.popover.style.left, '206px');
});

test('programmatically open menus without hover or focus reposition on resize and sticky scroll', () => {
  const state = setup();
  state.window.innerWidth = 360;
  state.window.innerHeight = 240;
  state.width(344);
  state.rect({ left: 170, width: 42, bottom: 68 });
  state.window.dispatchEvent(new Event('resize'));
  assert.equal(state.popover.style.left, '8px');
  assert.equal(state.popover.style['--topbar-popover-available-height'], '164px');
  state.rect({ left: 170, width: 42, bottom: 58 });
  state.document.dispatchEvent(new Event('scroll'));
  assert.equal(state.popover.style.top, '58px');
  assert.equal(state.popover.style['--topbar-popover-available-height'], '174px');
});
