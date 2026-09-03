import { createContext, useContext, type PropsWithChildren } from 'react';
import { usePolledJson } from '../shared/api/usePolledJson';
import { useAdminApp } from './AdminAppContext';
import type { AdminAppBootstrap } from './types';

export interface NotificationCenterPayload {
  actionableCount: number;
  items: { id: string; title: string; detail: string; createdLabel: string; href: string }[];
  reviewAllHref: string;
}
const notificationEvents = ['endervault:notifications-changed'];
const routeEvents = ['endervault:outbound-route-changed'];
function useShellStatusData() {
  const { bootstrap } = useAdminApp();
  const notifications = usePolledJson<NotificationCenterPayload>(
    '/api/v1/notifications', 20_000, undefined, notificationEvents);
  const outbound = usePolledJson<AdminAppBootstrap['outboundRoute']>(
    '/api/v1/outbound-route', 20_000, bootstrap.outboundRoute, routeEvents);
  return { notifications, outbound };
}
const ShellStatusContext = createContext<ReturnType<typeof useShellStatusData> | null>(null);

export function ShellStatusProvider({ children }: PropsWithChildren) {
  return <ShellStatusContext.Provider value={useShellStatusData()}>{children}</ShellStatusContext.Provider>;
}

export function useShellStatus() {
  const value = useContext(ShellStatusContext);
  if (!value) throw new Error('useShellStatus requires ShellStatusProvider.');
  return value;
}
