import { useEffect, useState } from 'react';
import { getSettings, showError } from '../settings-api';
import { canRetainSnapshot } from '../../shared/api/snapshot-errors';

export function useSettingsSnapshot<T>(endpoint: string) {
  const [stored, setStored] = useState<{ endpoint: string; value: T } | null>(null);
  const [failure, setFailure] = useState<{ endpoint: string; message: string } | null>(null);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let active = true;
    setFailure(null);
    getSettings<T>(endpoint)
      .then((value) => { if (active) setStored({ endpoint, value }); })
      .catch((reason: unknown) => {
        if (!active) return;
        const message = reason instanceof Error ? reason.message : 'Settings could not be loaded.';
        if (!canRetainSnapshot(reason)) setStored(null);
        setFailure({ endpoint, message });
        showError(reason);
      });
    return () => { active = false; };
  }, [endpoint, revision]);

  return {
    snapshot: stored?.endpoint === endpoint ? stored.value : null,
    error: failure?.endpoint === endpoint ? failure.message : '',
    refresh: () => setRevision((current) => current + 1),
  };
}
