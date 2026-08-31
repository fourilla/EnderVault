import { useEffect, useState } from 'react';
import { notify, postForm, toastError } from '../shared/api/form-api';
import { useAdminApp } from './AdminAppContext';
import { ShellPopover } from './ShellPopover';
import type { AdminAppBootstrap } from './types';

type OutboundRoute = AdminAppBootstrap['outboundRoute'];

const statusLabel = (route: OutboundRoute) => {
  if (route.statusClass === 'vpn-ready') return 'VPN connected';
  if (route.statusClass === 'vpn-unavailable') return 'VPN unavailable';
  return 'Direct';
};

export function OutboundRouteControl() {
  const { bootstrap } = useAdminApp();
  const [route, setRoute] = useState(bootstrap.outboundRoute);
  const [busy, setBusy] = useState(false);

  useEffect(() => setRoute(bootstrap.outboundRoute), [bootstrap.outboundRoute]);

  const toggle = async () => {
    if (busy) return;
    setBusy(true);
    try {
      const body = await postForm('/api/v1/outbound-route', { route: route.nextRoute });
      if (body.route) setRoute(body.route as OutboundRoute);
      notify(body);
    } catch (reason) {
      toastError(reason, 'The outbound route could not be changed.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <ShellPopover
      id="outbound-route"
      icon={route.iconClass}
      label={route.title}
      triggerClassName={`topbar-route-toggle route-${route.statusClass}`}
      onTriggerClick={() => void toggle()}
    >
      <strong className="topbar-control-title">Outbound VPN</strong>
      <p>Routes supported NAS outbound requests through the configured VPN proxy.</p>
      <div className="topbar-control-status">
        <span>Current route</span>
        <strong className={route.vpnSelected ? (route.vpnReady ? 'is-active' : 'is-error') : undefined}>
          {statusLabel(route)}
        </strong>
      </div>
      <small>{busy ? 'Changing route...' : 'Click the shield to switch routes.'}</small>
    </ShellPopover>
  );
}
