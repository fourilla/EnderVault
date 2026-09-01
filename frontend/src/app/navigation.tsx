import { lazy, type ComponentType, type LazyExoticComponent } from 'react';
import { matchPath } from 'react-router-dom';
import type { AdminAppCapabilities } from './types';

export type NavigationPlacement = 'sidebar' | 'apps' | 'account' | 'context';
export type NavigationGroup = 'sharing' | 'transfers' | 'operations' | 'notes' | 'application' | 'account';
export type NavigationCapability = keyof AdminAppCapabilities;

interface NavigationEntryBase {
  id: string;
  path: string;
  label: string;
  icon: string;
  placement?: NavigationPlacement;
  group?: NavigationGroup;
  requiredCapability?: NavigationCapability;
  stickyNotePageKey?: string;
}

export interface SpaNavigationEntry extends NavigationEntryBase {
  surface: 'spa';
  component: LazyExoticComponent<ComponentType>;
}

export interface SpaAliasNavigationEntry extends NavigationEntryBase {
  surface: 'spa-alias';
}

export type NavigationEntry = SpaNavigationEntry | SpaAliasNavigationEntry;

const lazyNamed = <TModule, TName extends keyof TModule>(
  loader: () => Promise<TModule>,
  name: TName,
) => lazy(async () => ({ default: (await loader())[name] as ComponentType }));

export const navigationEntries: readonly NavigationEntry[] = [
  {
    id: 'dashboard',
    path: '/admin/dashboard',
    label: 'Dashboard',
    icon: 'fas fa-gauge-high',
    placement: 'sidebar',
    surface: 'spa',
    component: lazyNamed(() => import('../dashboard/DashboardApp'), 'DashboardApp'),
  },
  {
    id: 'files',
    path: '/files',
    label: 'Files',
    icon: 'fas fa-folder',
    placement: 'sidebar',
    surface: 'spa',
    component: lazyNamed(() => import('../files/BrowserApp'), 'BrowserApp'),
  },
  {
    id: 'file-detail',
    path: '/files/detail',
    label: 'File details',
    icon: 'fas fa-circle-info',
    surface: 'spa',
    component: lazyNamed(() => import('../file-detail/FileDetailApp'), 'FileDetailApp'),
  },
  {
    id: 'recent',
    path: '/files/recent',
    label: 'Recent',
    icon: 'fas fa-clock',
    placement: 'sidebar',
    surface: 'spa',
    component: lazyNamed(() => import('../recent/RecentApp'), 'RecentApp'),
  },
  {
    id: 'bookmarks',
    path: '/files/bookmarks',
    label: 'Bookmarks',
    icon: 'fas fa-bookmark',
    placement: 'sidebar',
    surface: 'spa',
    component: lazyNamed(() => import('../bookmarks/BookmarksApp'), 'BookmarksApp'),
  },
  {
    id: 'bookmark-detail',
    path: '/files/bookmarks/detail',
    label: 'Bookmark details',
    icon: 'fas fa-circle-info',
    surface: 'spa',
    component: lazyNamed(() => import('../bookmarks/BookmarkDetailApp'), 'BookmarkDetailApp'),
  },
  {
    id: 'trash',
    path: '/admin/trash',
    label: 'Trash',
    icon: 'fas fa-trash-can',
    placement: 'sidebar',
    surface: 'spa',
    component: lazyNamed(() => import('../trash/TrashApp'), 'TrashApp'),
  },
  {
    id: 'favorites',
    path: '/files/favorites',
    label: 'Favorites',
    icon: 'fas fa-star',
    surface: 'spa',
    component: lazyNamed(() => import('../favorites/FavoritesApp'), 'FavoritesApp'),
  },
  {
    id: 'shared-links',
    path: '/admin/shares',
    label: 'Shared links',
    icon: 'fas fa-link',
    placement: 'apps',
    group: 'sharing',
    surface: 'spa',
    component: lazyNamed(() => import('../shares/SharedLinksApp'), 'SharedLinksApp'),
  },
  {
    id: 'file-requests',
    path: '/admin/file-requests',
    label: 'File requests',
    icon: 'fas fa-inbox',
    placement: 'apps',
    group: 'sharing',
    requiredCapability: 'fileRequests',
    surface: 'spa',
    component: lazyNamed(() => import('../file-requests/FileRequestsApp'), 'FileRequestsApp'),
  },
  {
    id: 'file-request-detail',
    path: '/admin/file-requests/:id',
    label: 'File request details',
    icon: 'fas fa-inbox',
    requiredCapability: 'fileRequests',
    surface: 'spa',
    component: lazyNamed(() => import('../file-requests/FileRequestDetailApp'), 'FileRequestDetailApp'),
  },
  {
    id: 'remote-downloads',
    path: '/admin/utils/remote-download',
    label: 'Remote download',
    icon: 'fas fa-cloud-arrow-down',
    placement: 'apps',
    group: 'transfers',
    requiredCapability: 'remoteDownloads',
    stickyNotePageKey: 'remote-download',
    surface: 'spa',
    component: lazyNamed(() => import('../remote-download/RemoteDownloadApp'), 'RemoteDownloadApp'),
  },
  {
    id: 'activity-logs',
    path: '/admin/logs',
    label: 'Activity logs',
    icon: 'fas fa-clock-rotate-left',
    placement: 'apps',
    group: 'operations',
    surface: 'spa',
    component: lazyNamed(() => import('../activity-logs/ActivityLogsApp'), 'ActivityLogsApp'),
  },
  {
    id: 'pending-decisions',
    path: '/admin/pending-decisions',
    label: 'Pending decisions',
    icon: 'fas fa-list-check',
    surface: 'spa',
    component: lazyNamed(() => import('../pending-decisions/PendingDecisionsApp'), 'PendingDecisionsApp'),
  },
  {
    id: 'metadata-inspector',
    path: '/admin/metadata',
    label: 'Metadata inspector',
    icon: 'fas fa-magnifying-glass-chart',
    placement: 'apps',
    group: 'operations',
    requiredCapability: 'metadataInspector',
    surface: 'spa',
    component: lazyNamed(() => import('../metadata/MetadataInspectorApp'), 'MetadataInspectorApp'),
  },
  {
    id: 'vpn-status',
    path: '/admin/vpn',
    label: 'VPN status',
    icon: 'fas fa-shield-halved',
    surface: 'spa',
    component: lazyNamed(() => import('../vpn/VpnStatusApp'), 'VpnStatusApp'),
  },
  {
    id: 'sticky-notes',
    path: '/admin/sticky-notes',
    label: 'Sticky note list',
    icon: 'fas fa-note-sticky',
    surface: 'spa',
    component: lazyNamed(() => import('../sticky-notes/StickyNoteListApp'), 'StickyNoteListApp'),
  },
  {
    id: 'settings',
    path: '/admin/settings',
    label: 'Settings',
    icon: 'fas fa-gear',
    placement: 'apps',
    group: 'application',
    surface: 'spa',
    component: lazyNamed(() => import('../settings/SettingsApp'), 'SettingsApp'),
  },
  {
    id: 'account-settings',
    path: '/admin/settings?section=account',
    label: 'Account settings',
    icon: 'fas fa-user-gear',
    placement: 'account',
    group: 'account',
    surface: 'spa-alias',
  },
  {
    id: 'passkeys',
    path: '/admin/settings?section=passkeys',
    label: 'Passkeys',
    icon: 'fas fa-key',
    placement: 'account',
    group: 'account',
    surface: 'spa-alias',
  },
  {
    id: 'active-sessions',
    path: '/admin/sessions',
    label: 'Active sessions',
    icon: 'fas fa-laptop',
    placement: 'account',
    group: 'account',
    surface: 'spa',
    component: lazyNamed(() => import('../sessions/ActiveSessionsApp'), 'ActiveSessionsApp'),
  },
] as const;

export function navigationFor(placement: NavigationPlacement): NavigationEntry[] {
  return navigationEntries.filter((entry) => entry.placement === placement);
}

export function navigationEntryAvailable(
  entry: NavigationEntry,
  capabilities: AdminAppCapabilities,
): boolean {
  return !entry.requiredCapability || capabilities[entry.requiredCapability];
}

export function spaRoutes(): SpaNavigationEntry[] {
  return navigationEntries.filter((entry): entry is SpaNavigationEntry => entry.surface === 'spa');
}

export function navigationEntryForPathname(pathname: string): NavigationEntry | undefined {
  return navigationEntries.find((entry) => entry.surface === 'spa'
    && Boolean(matchPath({ path: entry.path, end: true }, pathname)));
}

export function isSpaNavigationUrl(url: string): boolean {
  try {
    const target = new URL(url, window.location.origin);
    return target.origin === window.location.origin
      && spaRoutes().some((entry) => Boolean(matchPath({ path: entry.path, end: true }, target.pathname)));
  } catch {
    return false;
  }
}
