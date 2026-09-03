export function canRetainSnapshot(reason: unknown): boolean {
  if (!reason || typeof reason !== 'object') return true;
  const { status, sessionExpired } = reason as { status?: number; sessionExpired?: boolean };
  if (sessionExpired) return false;
  // Network/temporary server failures may keep the last view; denied/missing resources may not.
  return status == null || status < 400 || status >= 500 || status === 408 || status === 429;
}
