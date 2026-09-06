import assert from 'node:assert/strict';
import test from 'node:test';
import { createMemoryRouter } from 'react-router-dom';
import { shouldBlockUnsaved } from '../src/shared/dialogs/unsaved-navigation.ts';

function setup(entries) {
  const router = createMemoryRouter([{ path: '*', element: null }], { initialEntries: entries });
  let dirty = true;
  const check = ({ currentLocation, nextLocation }) => shouldBlockUnsaved(dirty, currentLocation, nextLocation);
  router.getBlocker('unsaved', check);
  return { router, blocker: () => router.getBlocker('unsaved', check), clean: () => { dirty = false; } };
}

test('text editor navigation waits for confirmation; cancel preserves the original URL; proceed reaches the requested route', async () => {
  const s = setup(['/files/detail?path=note.txt']);
  try {
    await s.router.navigate('/files');
    assert.equal(s.blocker().state, 'blocked');
    assert.equal(s.router.state.location.pathname, '/files/detail');
    s.blocker().reset();
    assert.equal(s.router.state.location.search, '?path=note.txt');
    await s.router.navigate('/admin/dashboard');
    s.blocker().proceed();
    assert.equal(s.router.state.location.pathname, '/admin/dashboard');
  } finally { s.router.dispose(); }
});

test('browser back and settings query changes are blocked, saved changes and same-page hash navigation are allowed', async () => {
  const s = setup(['/files', '/admin/settings?section=files']);
  try {
    await s.router.navigate(-1);
    assert.equal(s.blocker().state, 'blocked');
    s.blocker().reset();
    await s.router.navigate('/admin/settings?section=uploads');
    assert.equal(s.blocker().state, 'blocked');
    s.blocker().reset();
    await s.router.navigate('/admin/settings?section=files#appearance');
    assert.equal(s.router.state.location.hash, '#appearance');
    s.clean();
    await s.router.navigate('/files');
    assert.equal(s.router.state.location.pathname, '/files');
  } finally { s.router.dispose(); }
});
