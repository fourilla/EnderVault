export function formatBytes(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value) || value < 0) return 'Unavailable';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let size = value;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) { size /= 1024; unit++; }
  return `${size.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
}
