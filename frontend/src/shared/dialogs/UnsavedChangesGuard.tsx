import { useCallback, useId } from 'react';
import { useBlocker } from 'react-router-dom';
import { AppDialog } from './AppDialog';
import { shouldBlockUnsaved } from './unsaved-navigation';

export function UnsavedChangesGuard({ dirty, message }: { dirty: boolean; message: string }) {
  const titleId = useId();
  const shouldBlock = useCallback(({ currentLocation, nextLocation }: {
    currentLocation: { pathname: string; search: string };
    nextLocation: { pathname: string; search: string };
  }) => shouldBlockUnsaved(dirty, currentLocation, nextLocation), [dirty]);
  const blocker = useBlocker(shouldBlock);
  const stay = () => { if (blocker.state === 'blocked') blocker.reset(); };
  return <AppDialog open={blocker.state === 'blocked'} onDismiss={stay}
    className="text-input-dialog" labelledBy={titleId}>
    <div className="text-input-card">
      <header className="text-input-header">
        <div><h2 id={titleId}>Leave without saving?</h2><p>{message}</p></div>
        <button type="button" className="ghost icon-button" title="Stay" aria-label="Stay" onClick={stay}>
          <i className="fas fa-xmark" aria-hidden="true" />
        </button>
      </header>
      <div className="text-input-actions">
        <button type="button" className="ghost" onClick={stay}>Stay</button>
        <button type="button" className="danger" onClick={() => {
          if (blocker.state === 'blocked') blocker.proceed();
        }}>Leave without saving</button>
      </div>
    </div>
  </AppDialog>;
}
