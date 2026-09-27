import { useState } from 'react';
import { toastError } from '../shared/api/form-api';
import { PrepareDirectoryMergeButton } from '../directory-merges/PrepareDirectoryMergeButton';
import { resolvePendingDecision } from './pending-decision-api';
import type { PendingFileDecision, PendingFileDecisionAction } from './types';

export function PendingDecisionActions({ decision, nested = false, labelled = false, resolved, mergeStarted, busyChanged }: {
  decision: PendingFileDecision; nested?: boolean; resolved: (id: string) => void;
  labelled?: boolean;
  mergeStarted?: () => void; busyChanged?: (busy: boolean) => void;
}) {
  const [busy, setBusy] = useState(false);
  const act = async (action: PendingFileDecisionAction) => {
    if (busy) return;
    setBusy(true); busyChanged?.(true);
    try {
      let filename: string | undefined;
      if (action === 'SAVE_AS') {
        const value = await window.EnderVault!.askTextInput({ nested,
          title: decision.directory ? 'Save pending directory as' : 'Save pending file as',
          label: decision.directory ? 'Directory name' : 'File name',
          initialValue: decision.originalFilename, confirmLabel: 'Save',
        });
        if (!value) return;
        filename = value;
      }
      if (action === 'REPLACE' || action === 'DISCARD') {
        const confirmed = await window.EnderVault!.askConfirmation({ nested,
          title: action === 'REPLACE' ? 'Replace existing file' : 'Discard pending item',
          message: action === 'REPLACE' ? 'Replace the existing destination file with this staged file?'
            : decision.directory ? 'Permanently discard this staged directory and its contents?' : 'Permanently discard this staged file?',
          confirmLabel: action === 'REPLACE' ? 'Replace' : 'Discard', danger: true,
        });
        if (!confirmed) return;
      }
      const result = await resolvePendingDecision(decision.id, action, { filename, replaceConfirmed: action === 'REPLACE' });
      resolved(result.removedId);
    } catch (reason) { toastError(reason, 'Pending item resolution failed.'); }
    finally { setBusy(false); busyChanged?.(false); }
  };
  const buttonClass = labelled ? 'icon-text-button' : 'icon-button action-icon';
  return <div className={labelled ? 'pending-decision-action-grid' : 'table-actions'}>
    {decision.directory && <PrepareDirectoryMergeButton pendingId={decision.id} mergeId={decision.mergeId}
      labelled={labelled}
      disabled={busy} started={mergeStarted} busyChanged={(value) => { setBusy(value); busyChanged?.(value); }} />}
    {!decision.mergeId && <>
      <button type="button" className={`ghost ${buttonClass}`} title={labelled ? 'Keep the existing item and save this one with an available numbered name, such as name - 1.txt or folder - 1.' : 'Keep both'} aria-label="Keep both" disabled={busy}
        onClick={() => void act('KEEP_BOTH')}><i className="fas fa-copy" aria-hidden="true" />{labelled && <span>Keep both</span>}</button>
      <button type="button" className={`ghost ${buttonClass}`} title={labelled ? 'Choose a different name and save this item without replacing an existing item.' : 'Save as'} aria-label="Save as" disabled={busy}
        onClick={() => void act('SAVE_AS')}><i className="fas fa-pen" aria-hidden="true" />{labelled && <span>Save as</span>}</button>
      {!decision.directory && <button type="button" className={`danger ${buttonClass}`} title={labelled ? 'Replace the existing destination file with this staged file after confirmation.' : 'Replace existing file'}
        aria-label="Replace existing file" disabled={busy} onClick={() => void act('REPLACE')}>
        <i className="fas fa-file-arrow-down" aria-hidden="true" />{labelled && <span>Replace existing file</span>}</button>}
      <button type="button" className={`danger ${buttonClass}`} title={labelled ? 'Delete this staged item after confirmation. The existing destination item is kept.' : 'Discard staged item'} aria-label="Discard staged item"
        disabled={busy} onClick={() => void act('DISCARD')}><i className="fas fa-trash-can" aria-hidden="true" />{labelled && <span>Discard staged item</span>}</button>
    </>}
  </div>;
}
