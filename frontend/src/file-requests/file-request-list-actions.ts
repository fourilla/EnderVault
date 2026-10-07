import type { NavigateFunction } from 'react-router-dom';
import { copyListLink, type ListItemAction } from '../shared/browser/list-item-actions';
import { deleteFileRequest, revokeFileRequest, resolveFileRequestSelection } from './file-request-api';
import type { FileRequestItem } from './types';

const details = (item: FileRequestItem) => `/admin/file-requests/${item.id}`;

export const fileRequestListActions = (navigate: NavigateFunction): readonly ListItemAction<FileRequestItem>[] => [
  { id: 'request-details', label: 'Details', icon: 'fas fa-circle-info', group: 'open', supports: () => true,
    href: details, execute: (item) => navigate(details(item)) },
  { id: 'request-copy', label: 'Copy request link', icon: 'fas fa-link', group: 'copy', supports: () => true,
    execute: (item) => copyListLink(item.url, 'File request link copied.') },
  { id: 'request-revoke', label: 'Revoke', icon: 'fas fa-link-slash', group: 'mutate', danger: true,
    supports: (item) => item.active, changesList: true,
    confirmation: { title: 'Revoke file request',
      message: 'Revoke this file request? Active uploads will stop on their next protocol request.',
      confirmLabel: 'Revoke', danger: true }, execute: (item) => revokeFileRequest(item.id),
    bulk: { confirmation: (count) => ({ title: 'Revoke selected file requests',
      message: `Revoke these ${count} file requests? Active uploads will stop on their next protocol request.`,
      confirmLabel: 'Revoke', danger: true }), execute: (ids) => resolveFileRequestSelection(ids, 'REVOKE') } },
  { id: 'request-delete', label: 'Delete', icon: 'fas fa-trash-can', group: 'mutate', danger: true,
    supports: (item) => !item.active, changesList: true,
    confirmation: { title: 'Delete file request',
      message: 'Delete this file request record? Pending files and active uploads must be resolved first.',
      confirmLabel: 'Delete', danger: true }, execute: (item) => deleteFileRequest(item.id),
    bulk: { confirmation: (count) => ({ title: 'Delete selected file requests',
      message: `Delete these ${count} file request records? Requests with active uploads or pending files cannot be deleted.`,
      confirmLabel: 'Delete', danger: true }), execute: (ids) => resolveFileRequestSelection(ids, 'DELETE') } },
];

export const fileRequestItemKey = (item: FileRequestItem) => item.id;
export const fileRequestItemIdentity = (item: FileRequestItem) => JSON.stringify([
  item.id, item.title, item.destinationPath, item.url, item.active,
]);
