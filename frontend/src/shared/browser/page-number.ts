export function resolvePage(value: string, current: number, total: number): number {
  const parsed = Number.parseInt(value, 10);
  return Math.max(1, Math.min(total, Number.isFinite(parsed) ? parsed : current));
}
