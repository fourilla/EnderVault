package io.github.fourilla.endervault.outbound.vpn.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.fourilla.endervault.config.NasProperties;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GluetunControlClientTest {

    private static final String API_KEY = "test_control_api_key_123456789";

    @TempDir
    Path temporaryDirectory;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsRunningStatusAndPublicIpWithApiKey() throws Exception {
        AtomicReference<String> receivedKey = new AtomicReference<>();
        server = server();
        server.createContext("/v1/vpn/status", exchange -> {
            receivedKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            respond(exchange, 200, "{\"status\":\"running\"}");
        });
        server.createContext("/v1/publicip/ip", exchange ->
                respond(exchange, 200, "{\"public_ip\":\"203.0.113.17\"}"));
        server.start();

        GluetunControlClient.Inspection inspection = client().inspect();

        assertThat(inspection.state()).isEqualTo(VpnControlState.RUNNING);
        assertThat(inspection.publicIp()).isEqualTo("203.0.113.17");
        assertThat(receivedKey.get()).isEqualTo(API_KEY);
    }

    @Test
    void doesNotRequestPublicIpWhenVpnIsStopped() throws Exception {
        AtomicInteger publicIpRequests = new AtomicInteger();
        server = server();
        server.createContext("/v1/vpn/status", exchange ->
                respond(exchange, 200, "{\"status\":\"stopped\"}"));
        server.createContext("/v1/publicip/ip", exchange -> {
            publicIpRequests.incrementAndGet();
            respond(exchange, 200, "{\"public_ip\":\"203.0.113.17\"}");
        });
        server.start();

        GluetunControlClient.Inspection inspection = client().inspect();

        assertThat(inspection.state()).isEqualTo(VpnControlState.STOPPED);
        assertThat(inspection.publicIp()).isBlank();
        assertThat(publicIpRequests).hasValue(0);
    }

    @Test
    void sendsAuthenticatedPutCommand() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = server();
        server.createContext("/v1/vpn/status", exchange -> {
            method.set(exchange.getRequestMethod());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(exchange.getRequestHeaders().getFirst("X-API-Key")).isEqualTo(API_KEY);
            respond(exchange, 200, "{}");
        });
        server.start();

        client().setRunning(false);

        assertThat(method.get()).isEqualTo("PUT");
        assertThat(body.get()).contains("\"status\":\"stopped\"");
    }

    @Test
    void rejectsRedirectInsteadOfFollowingIt() throws Exception {
        server = server();
        server.createContext("/v1/vpn/status", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://example.com/");
            respond(exchange, 302, "");
        });
        server.start();

        assertThatThrownBy(() -> client().inspect())
                .isInstanceOf(VpnControlException.class)
                .hasMessageContaining("HTTP 302");
    }

    private GluetunControlClient client() throws IOException {
        Path keyFile = temporaryDirectory.resolve("control-api.key");
        Files.writeString(keyFile, API_KEY + "\n", StandardCharsets.UTF_8);
        NasProperties properties = new NasProperties();
        NasProperties.Vpn vpn = properties.getOutbound().getVpn();
        vpn.setEnabled(true);
        vpn.setControlUrl("http://localhost:" + server.getAddress().getPort());
        vpn.setControlApiKeyFile(keyFile.toString());
        vpn.setControlRequestTimeoutMs(1000);
        return new GluetunControlClient(properties, new ObjectMapper());
    }

    private HttpServer server() throws IOException {
        return HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
