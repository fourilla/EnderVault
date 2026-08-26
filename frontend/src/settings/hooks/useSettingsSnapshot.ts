import { useEffect, useState } from 'react';
import { getSettings, showError } from '../settings-api';

export function useSettingsSnapshot<T>(endpoint: string) {
  const [snapshot, setSnapshot] = useState<T | null>(null);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let active = true;
    setSnapshot(null);
    setError('');
    getSettings<T>(endpoint)
      .then((value) => { if (active) setSnapshot(value); })
      .catch((reason: unknown) => {
        const message = reason instanceof Error ? reason.message : 'Settings could not be loaded.';
        if (active) setError(message);
        showError(reason);
      });
    return () => { active = false; };
  }, [endpoint, revision]);

  return { snapshot, error, refresh: () => setRevision((current) => current + 1) };
}
