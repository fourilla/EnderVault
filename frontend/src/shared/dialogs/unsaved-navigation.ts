export function shouldBlockUnsaved(dirty: boolean, current: { pathname: string; search: string },
  next: { pathname: string; search: string }) {
  return dirty && (current.pathname !== next.pathname || current.search !== next.search);
}
