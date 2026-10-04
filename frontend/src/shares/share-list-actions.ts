import { copyListLink, type ListItemAction } from '../shared/browser/list-item-actions';
import { deleteShare, revokeShare } from './share-api';
import type { ShareLink } from './types';

export const shareListActions: readonly ListItemAction<ShareLink>[] = [
  { id: 'share-copy', label: 'Copy link', icon: 'fas fa-link', group: 'copy', supports: () => true,
    execute: (item) => copyListLink(item.url, 'Share link copied.') },
  { id: 'share-copy-download', label: 'Copy direct download link', icon: 'fas fa-file-arrow-down', group: 'copy',
    supports: (item) => Boolean(item.directDownloadUrl),
    execute: (item) => copyListLink(item.directDownloadUrl!, 'Direct download link copied.') },
  { id: 'share-revoke', label: 'Revoke', icon: 'fas fa-link-slash', group: 'mutate', danger: true,
    supports: (item) => item.active, changesList: true, execute: (item) => revokeShare(item.token) },
  { id: 'share-delete', label: 'Delete', icon: 'fas fa-trash-can', group: 'mutate',
    supports: () => true, changesList: true, execute: (item) => deleteShare(item.token) },
];

export const shareItemKey = (item: ShareLink) => item.token;
export const shareItemIdentity = (item: ShareLink) => JSON.stringify([
  item.token, item.path, item.type, item.url, item.directDownloadUrl, item.active,
]);
