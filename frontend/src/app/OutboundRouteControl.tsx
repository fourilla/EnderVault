import { useState } from 'react';
import { notify, postForm, toastError } from '../shared/api/form-api';
import { AppNavigationLink } from './AppNavigationLink';
import { useShellStatus } from './ShellStatusContext';
import { ShellPopover } from './ShellPopover';
import type { AdminAppBootstrap } from './types';

type OutboundRoute = AdminAppBootstrap['outboundRoute'];

const statusLabel = (route: OutboundRoute) => {
  if (route.statusClass === 'vpn-ready') return 'VPN connected';
  if (route.statusClass === 'vpn-unavailable') return 'VPN unavailable';
  return 'Direct';
};

export function OutboundRouteControl() {
  const { outbound } = useShellStatus();
  const route = outbound.data!;
  const [busy, setBusy] = useState(false);

  const toggle = async () => {
    if (busy) return;
    setBusy(true);
    try {
      const body = await postForm('/api/v1/outbound-route', { route: route.nextRoute });
      if (body.route) outbound.accept(body.route as OutboundRoute);
      void outbound.refresh();
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
      triggerDataAttributes={{ 'aria-pressed': String(route.vpnSelected), 'aria-busy': String(busy) }}
      onTriggerClick={() => void toggle()}
    >
      <strong className="topbar-control-title">Outbound VPN</strong>
      <p>Routes supported NAS outbound requests through the configured VPN proxy.</p>
      <div className="topbar-control-status">
        <span>Current route</span>
        <strong className={route.vpnSelected ? (route.vpnReady ? 'is-active' : 'is-error') : undefined}>
          {outbound.error ? 'Status unavailable' : statusLabel(route)}
        </strong>
      </div>
      {busy && <small>Changing route...</small>}
      <div className="topbar-control-menu-actions">
        <AppNavigationLink className="ghost button-link icon-text-button" href="/admin/vpn">
          <i className="fas fa-chart-line" aria-hidden="true" />
          <span>VPN status</span>
        </AppNavigationLink>
      </div>
    </ShellPopover>
  );
}
