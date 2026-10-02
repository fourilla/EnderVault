import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const source = (path) => readFileSync(new URL(`../src/app/${path}`, import.meta.url), 'utf8');

test('mobile sidebar reuses the existing navigation and modal lifecycle', () => {
  const drawer = source('SidebarDrawer.tsx');
  assert.match(drawer, /<AppDialog open=\{open\} onDismiss=\{close\} dismissOnBackdrop/);
  assert.match(drawer, /\{children\}/);
  assert.match(drawer, /id="sidebar-drawer-title" className="sr-only"/);
  assert.doesNotMatch(drawer, /<button|sidebar-drawer-heading/);
  assert.match(drawer, /closest\('a\[href\]'\)/);
  const shell = source('AppShell.tsx');
  assert.match(shell, /sidebar\.mobile \? <SidebarDrawer/);
  assert.match(shell, /useSidebarLayout\(location.key\)/);
  assert.match(shell, /const \[favoritesOpen, setFavoritesOpen\] = useState\(true\)/);
  assert.match(shell, /<AdminSidebar favoritesOpen=\{favoritesOpen\}/);
  assert.doesNotMatch(source('AdminSidebar.tsx'), /useState/);
});

test('drawer state is transient and desktop preference is preserved across breakpoints', () => {
  const hook = source('useSidebarLayout.ts');
  assert.match(hook, /max-width: 720px/);
  assert.match(hook, /setMobile\(query.matches\); setDrawerOpen\(false\)/);
  assert.match(hook, /removeEventListener\('change', update\)/);
  assert.match(hook, /useEffect\(closeDrawer, \[routeKey, closeDrawer\]\)/);
  assert.match(hook, /localStorage.setItem\('endervault.sidebar.collapsed', String\(collapsed\)\)/);
  assert.doesNotMatch(hook, /localStorage.setItem\([^\n]*drawerOpen/);
  const css = source('app-shell.css');
  assert.doesNotMatch(css, /\.sidebar-collapse-toggle\s*\{\s*display: none/);
  assert.match(css, /\.sidebar-drawer \.sidebar-nav \{ display: grid;/);
});
