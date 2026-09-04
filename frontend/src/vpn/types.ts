export interface VpnHealthStatus {
  state: string;
  label: string;
  statusClass: string;
  proxyReachable: boolean;
  routeReady: boolean;
  proxyEndpoint: string;
  tunnelHealthEndpoint: string;
  checkedAtLabel: string;
  latencyLabel: string;
  detail: string;
}

export interface VpnRuntimeStatus {
  state: string;
  label: string;
  statusClass: string;
  controllable: boolean;
  running: boolean;
  publicIp: string;
  checkedAtLabel: string;
  latencyLabel: string;
  detail: string;
  profileName: string;
  routeLabel: string;
  vpnRouteSelected: boolean;
  activeVpnTasks: number;
  health: VpnHealthStatus;
}

export interface VpnControlPayload {
  ok: boolean;
  notification?: unknown;
  vpn: VpnRuntimeStatus | null;
}

export type VpnCommand = 'refresh' | 'connect' | 'reconnect' | 'disconnect';
