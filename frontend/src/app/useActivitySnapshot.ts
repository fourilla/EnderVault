import { useEffect, useState } from 'react';

export type ActivitySnapshot = ReturnType<NonNullable<Window['EnderVaultActivity']>['snapshot']>;
export type ActivityItem = ActivitySnapshot['items'][number];
const empty: ActivitySnapshot = { activeCount: 0, finishedCount: 0, totalCount: 0, items: [] };
const read = () => window.EnderVaultActivity?.snapshot() ?? empty;

export function useActivitySnapshot() {
  const [snapshot, setSnapshot] = useState(read);
  useEffect(() => {
    const changed = () => setSnapshot(read());
    document.addEventListener('endervault:activity-changed', changed);
    changed();
    return () => document.removeEventListener('endervault:activity-changed', changed);
  }, []);
  return snapshot;
}
