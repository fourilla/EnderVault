import assert from 'node:assert/strict';
import test from 'node:test';
import { domains, listHarness, flush, nodes, rows, checkbox, selected, button, compile } from './helpers/selectable-list-harness.mjs';
import vm from 'node:vm';

for (const domain of domains) {
  test(`${domain.scope} shares Shift selection, select all and native shortcut exclusions`, async t => {
    const h = listHarness(t, domain); h.render(); await h.finish(0, ['one', 'two', 'three']);
    const tree = h.render();
    assert.equal(nodes(tree).find(node => node.type?.name === 'StableTable').props.columns[0], 'select');
    const click = (row, modifiers) => row.props.onClickCapture({ target: { closest: () => null }, preventDefault() {}, stopPropagation() {}, ...modifiers });
    click(rows(tree)[0], { ctrlKey: true }); click(rows(h.render())[2], { shiftKey: true });
    assert.deepEqual(selected(h.render()), ['one', 'two', 'three']);
    h.key({ key: 'Escape', target: h.document.body, preventDefault() {} });
    assert.deepEqual(selected(h.render()), []);
    h.key({ key: 'a', ctrlKey: true, target: h.document.body, preventDefault() {} });
    assert.deepEqual(selected(h.render()), ['one', 'two', 'three']);
    h.key({ key: 'Delete', target: { closest: () => ({}) }, preventDefault: () => assert.fail('Native input owns key') });
    assert.equal(h.mutations.length, 0);
    const header = nodes(h.render()).find(node => node.type?.name === 'SelectionHeader');
    header.props.onChange(false); assert.deepEqual(selected(h.render()), []);
  });
  test(`${domain.scope} single/menu actions share definitions; multi menu exposes only supported bulk action`, async t => {
    const h = listHarness(t, domain); h.render(); await h.finish(0, ['one', 'two']); h.render();
    const visible = context => h.menu.actions().filter(action => action.visible(context));
    assert.ok(visible(h.context('one')).some(action => action.label === domain.deleteLabel));
    for (const row of rows(h.render())) checkbox(row).props.onChange({ currentTarget: { checked: true } });
    h.render();
    assert.deepEqual(Array.from(visible(h.context('one')), action => action.label), [domain.deleteLabel]);
    assert.equal(visible(h.context(null)).length, 0);
  });
  test(`${domain.scope} Delete makes one confirmed batch and retains failed selection on one reload`, async t => {
    const h = listHarness(t, domain); h.render(); await h.finish(0, ['one', 'two']); h.render();
    for (const row of rows(h.render())) checkbox(row).props.onChange({ currentTarget: { checked: true } });
    h.render();
    h.setBulkOutcome({ ok: true, succeededCount: 1, failedCount: 1, results: [
      { id: 'one', status: 'APPLIED', message: 'Done' }, { id: 'two', status: 'FAILED', message: 'Try again later' }] });
    h.key({ key: 'Delete', target: h.document.body, preventDefault() {} });
    await flush(); let tree = h.render();
    assert.equal(h.confirmations.length, 1);
    assert.equal(h.mutations.length, 1); assert.equal(h.mutations[0].name, domain.bulk);
    assert.deepEqual(Array.from(h.mutations[0].args[0]), ['one', 'two']);
    assert.deepEqual(rows(tree).map(row => row.key), ['two']);
    assert.deepEqual(selected(tree), ['two']);
    assert.ok(nodes(tree).some(node => node.props?.role === 'status' && node.props.children === 'Try again later'));
    assert.equal(h.requests.length, 2);
    await h.finish(1, ['two', 'new']); assert.deepEqual(selected(h.render()), ['two']);
  });
  test(`${domain.scope} cancelling confirmation changes nothing; leaving before confirmation never submits`, async t => {
    const h = listHarness(t, domain); h.render(); await h.finish(0, ['one']); h.render();
    checkbox(rows(h.render())[0]).props.onChange({ currentTarget: { checked: true } }); h.render();
    h.setConfirmation(async () => false);
    h.key({ key: 'Delete', target: h.document.body, preventDefault() {} }); await flush(); h.render();
    assert.equal(h.requests.length, 1); assert.deepEqual(selected(h.render()), ['one']);
    let finish; h.setConfirmation(() => new Promise(resolve => { finish = resolve; }));
    h.key({ key: 'Delete', target: h.document.body, preventDefault() {} });
    await h.router.navigate('/files/detail?path=elsewhere'); finish(true); await flush();
    assert.equal(h.mutations.length, 0);
  });
  test(`${domain.scope} lost batch response reads once without replay; failed read disables actions`, async t => {
    const h = listHarness(t, domain); h.render(); await h.finish(0, ['one']); h.render();
    checkbox(rows(h.render())[0]).props.onChange({ currentTarget: { checked: true } }); h.render();
    h.setBulkOutcome(new Error('Connection lost'));
    h.key({ key: 'Delete', target: h.document.body, preventDefault() {} }); await flush(); h.render();
    assert.equal(h.mutations.length, 1); assert.equal(h.requests.length, 2);
    assert.deepEqual(selected(h.render()), ['one']);
    h.requests[1].reject(new Error('Offline')); await flush();
    const tree = h.render(); assert.deepEqual(selected(tree), ['one']);
    assert.equal(button(tree, 'one', domain.deleteLabel).props.disabled, true);
    h.key({ key: 'Delete', target: h.document.body, preventDefault() {} }); await flush();
    assert.equal(h.mutations.length, 1); assert.equal(h.confirmations.length, 1);
  });
  test(`${domain.scope} changed selection cancels a pending confirmation before submission`, async t => {
    const h = listHarness(t, domain); h.render(); await h.finish(0, ['one', 'two']); h.render();
    checkbox(rows(h.render())[0]).props.onChange({ currentTarget: { checked: true } }); h.render();
    let finish; h.setConfirmation(() => new Promise(resolve => { finish = resolve; }));
    h.key({ key: 'Delete', target: h.document.body, preventDefault() {} });
    checkbox(rows(h.render())[1]).props.onChange({ currentTarget: { checked: true } }); h.render();
    finish(true); await flush(); h.render();
    assert.equal(h.mutations.length, 0); assert.equal(h.requests.length, 1);
  });
  if (domain.scope !== 'favorites') test(`${domain.scope} query changes clear selection while late refresh cannot replace newer data`, async t => {
    const h = listHarness(t, domain); h.render(); await h.finish(0, ['one']); h.render();
    checkbox(rows(h.render())[0]).props.onChange({ currentTarget: { checked: true } }); h.render();
    await h.router.navigate(domain.route + '?q=name:new'); h.render();
    assert.deepEqual(rows(h.render()), []); await h.finish(1, ['new']);
    assert.deepEqual(selected(h.render()), []);
    h.requests[0].resolve(domain.payload([domain.item('old')])); await flush();
    assert.deepEqual(rows(h.render()).map(row => row.key), ['new']);
  });
}

test('Favorites retains boundary buttons, disables them and never deletes underlying targets', async t => {
  const domain = domains[2], h = listHarness(t, domain); h.render(); await h.finish(0, ['folder', 'bookmark:uuid']);
  let tree = h.render();
  assert.equal(button(tree, 'folder', 'Move up').props.disabled, true);
  assert.equal(button(tree, 'bookmark:uuid', 'Move down').props.disabled, true);
  const context = h.context('folder'), up = h.menu.actions().find(action => action.label === 'Move up');
  assert.equal(up.disabled(context), true); await up.run(context); assert.equal(h.mutations.length, 0);
  await button(tree, 'folder', 'Move down').props.onClick(); await flush(); h.render();
  assert.equal(h.mutations[0].name, 'moveFavorite'); assert.deepEqual(Array.from(h.mutations[0].args), ['folder', 'down']);
  await h.finish(1, ['bookmark:uuid', 'folder']); tree = h.render();
  assert.equal(button(tree, 'folder', 'Move down').props.disabled, true);
  const open = h.menu.actions().find(action => action.label === 'Open'); await open.run(h.context('folder'));
  assert.deepEqual(h.opened, ['/files?path=folder']);
});

test('selected mutation transport uses one encoded request, validation and domain notification events', async () => {
  const selectedCode = await compile('shared/api/selected-item-api.ts');
  const calls = [], events = [], notifications = [];
  const form = { postEncodedForm: async (url, fields) => { calls.push({ url, fields }); return {
    ok: true, succeededCount: 1, failedCount: 1, results: [
      { id: fields.ids[0], status: 'APPLIED', message: 'Done' }, { id: fields.ids[1], status: 'FAILED', message: 'Failed' }] }; },
    notify: response => notifications.push(response) };
  const evaluate = (code, require) => { const module = { exports: {} }; vm.runInNewContext(code, {
    module, exports: module.exports, require, document: { dispatchEvent: event => events.push(event) },
    CustomEvent: class { constructor(type, config) { this.type = type; this.detail = config?.detail; } },
  }); return module.exports; };
  const validation = evaluate(await compile('shared/browser/list-item-actions.ts'), () => form);
  const selectedApi = evaluate(selectedCode, id => id.endsWith('/list-item-actions') ? validation : form);
  const favorite = evaluate(await compile('shared/api/favorite-api.ts'), id => id.endsWith('/selected-item-api') ? selectedApi : form);
  await favorite.removeSelectedFavorites(['photos', 'bookmark:uuid']);
  assert.equal(calls.length, 1); assert.equal(calls[0].url, '/api/v1/favorites/selected/resolve');
  assert.equal(calls[0].fields.action, 'REMOVE'); assert.equal(calls[0].fields.confirmed, true);
  assert.deepEqual(Array.from(calls[0].fields.ids), ['photos', 'bookmark:uuid']);
  assert.equal(events.filter(event => event.type === 'endervault:favorites-changed').length, 1);
  events.length = 0;
  const sticky = evaluate(await compile('sticky-notes/sticky-note-catalog-api.ts'), id => id.endsWith('/selected-item-api') ? selectedApi : form);
  await sticky.deleteSelectedStickyNotes(['one', 'two']);
  assert.equal(calls[1].fields.action, 'DELETE'); assert.equal(notifications.length, 2);
  assert.deepEqual(events.map(event => [event.type, event.detail.id]), [['endervault:sticky-note-deleted', 'one']]);
});

test('Favorites ignores an aborted refresh, retains surviving selection and never selects newly added rows', async t => {
  const domain = domains[2], h = listHarness(t, domain); h.render(); await h.finish(0, ['one', 'two']); h.render();
  checkbox(rows(h.render())[0]).props.onChange({ currentTarget: { checked: true } }); h.render();
  h.event('endervault:favorites-changed'); h.render();
  h.event('endervault:favorites-changed'); h.render();
  assert.equal(h.requests[1].signal.aborted, true);
  await h.finish(2, ['one', 'new']); assert.deepEqual(selected(h.render()), ['one']);
  await h.finish(1, ['old']); assert.deepEqual(rows(h.render()).map(row => row.key), ['one', 'new']);
});
