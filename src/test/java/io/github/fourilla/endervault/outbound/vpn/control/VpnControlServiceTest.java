package io.github.fourilla.endervault.outbound.vpn.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.fourilla.endervault.config.NasProperties;
import org.junit.jupiter.api.Test;

class VpnControlServiceTest {

    @Test
    void reportsDisabledWithoutCallingControlApi() {
        NasProperties properties = new NasProperties();
        GluetunControlClient client = mock(GluetunControlClient.class);

        VpnControlStatus status = new VpnControlService(properties, client).refresh();

        assertThat(status.state()).isEqualTo(VpnControlState.DISABLED);
    }

    @Test
    void reportsUnconfiguredWhenControlEndpointIsMissing() {
        NasProperties properties = enabledProperties();
        GluetunControlClient client = mock(GluetunControlClient.class);
        when(client.configured()).thenReturn(false);

        VpnControlStatus status = new VpnControlService(properties, client).refresh();

        assertThat(status.state()).isEqualTo(VpnControlState.UNCONFIGURED);
    }

    @Test
    void reportsRunningControlState() throws Exception {
        NasProperties properties = enabledProperties();
        GluetunControlClient client = mock(GluetunControlClient.class);
        when(client.configured()).thenReturn(true);
        when(client.inspect()).thenReturn(new GluetunControlClient.Inspection(
                VpnControlState.RUNNING,
                "203.0.113.17",
                "Running."
        ));

        VpnControlStatus status = new VpnControlService(properties, client).refresh();

        assertThat(status.state()).isEqualTo(VpnControlState.RUNNING);
        assertThat(status.publicIp()).isEqualTo("203.0.113.17");
    }

    @Test
    void disconnectSendsStopCommandAndRefreshesStatus() throws Exception {
        NasProperties properties = enabledProperties();
        GluetunControlClient client = mock(GluetunControlClient.class);
        when(client.configured()).thenReturn(true);
        when(client.inspect()).thenReturn(new GluetunControlClient.Inspection(
                VpnControlState.STOPPED,
                "",
                "Stopped."
        ));

        VpnControlStatus status = new VpnControlService(properties, client).disconnect();

        verify(client).setRunning(false);
        assertThat(status.state()).isEqualTo(VpnControlState.STOPPED);
    }

    @Test
    void scheduledRefreshRecoversFromVpnStartupRace() throws Exception {
        NasProperties properties = enabledProperties();
        GluetunControlClient client = mock(GluetunControlClient.class);
        when(client.configured()).thenReturn(true);
        when(client.inspect())
                .thenThrow(new VpnControlException("Control API is not ready."))
                .thenReturn(new GluetunControlClient.Inspection(
                        VpnControlState.RUNNING,
                        "203.0.113.17",
                        "Running."
                ));
        VpnControlService service = new VpnControlService(properties, client);

        assertThat(service.refresh().state()).isEqualTo(VpnControlState.UNAVAILABLE);

        service.scheduledRefresh();

        assertThat(service.current().state()).isEqualTo(VpnControlState.RUNNING);
        verify(client, times(2)).inspect();
    }

    private NasProperties enabledProperties() {
        NasProperties properties = new NasProperties();
        properties.getOutbound().getVpn().setEnabled(true);
        return properties;
    }
}
