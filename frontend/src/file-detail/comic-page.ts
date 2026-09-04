export function comicPageIndex(search: string, total: number, fallback = 0): number {
  const value = new URLSearchParams(search).get('comicPage');
  const page = value ? Number(value) - 1 : fallback;
  return Math.max(0, Math.min(Math.max(0, total - 1), Number.isFinite(page) ? Math.trunc(page) : fallback));
}
