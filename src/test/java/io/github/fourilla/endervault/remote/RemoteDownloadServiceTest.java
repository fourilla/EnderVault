package io.github.fourilla.endervault.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.auth.ClientIpResolver;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.outbound.OutboundHttpClientRegistry;
import io.github.fourilla.endervault.outbound.OutboundRouteStateService;
import io.github.fourilla.endervault.outbound.OutboundRouteUnavailableException;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthService;
import io.github.fourilla.endervault.outbound.vpn.VpnTunnelHealthProbe;
import io.github.fourilla.endervault.pending.PendingFileDecisionAction;
import io.github.fourilla.endervault.pending.PendingFileDecisionRepository;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

class RemoteDownloadServiceTest {

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d+)");

    @TempDir
    Path root;

    private HttpServer server;
    private NasProperties properties;
    private RemoteDownloadService remoteDownloadService;
    private RemoteDownloadTransferEngine transferEngine;
    private RemoteDownloadRequestTicketService ticketService;
    private OutboundRouteStateService outboundRouteStateService;
    private TemporaryArtifactRegistry temporaryArtifactRegistry;
    private PendingFileDecisionService pendingFileDecisionService;

    @BeforeEach
    void setUp() throws Exception {
        properties = new NasProperties();
        properties.getStorage().setRoot(root);
        properties.getRemoteDownload().setEnabled(true);
        properties.getRemoteDownload().setBlockPrivateNetworks(false);
        properties.getRemoteDownload().setAllowedPorts(List.of());
        properties.getRemoteDownload().setWorkerThreads(1);
        properties.getRemoteDownload().setMaxRetries(2);
        outboundRouteStateService = new OutboundRouteStateService(properties);

        temporaryArtifactRegistry = new TemporaryArtifactRegistry();
        StorageService storageService = new StorageService(
                properties,
                new FileActionRegistry(),
                temporaryArtifactRegistry
        );
        storageService.initialize();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ActivityLogService activityLogService = new ActivityLogService(objectMapper, properties);
        activityLogService.initialize();
        RemoteDownloadValidator validator = new RemoteDownloadValidator(properties);
        OutboundHttpClientRegistry registry = new OutboundHttpClientRegistry(
                new VpnProxyHealthService(properties, new VpnTunnelHealthProbe())
        );
        RemoteDownloadHttpClient httpClient = new RemoteDownloadHttpClient(properties, validator, registry);
        RemoteDownloadFileNameResolver fileNameResolver = new RemoteDownloadFileNameResolver();
        RemoteDownloadRetryPolicy retryPolicy = new RemoteDownloadRetryPolicy(properties);
        RemoteDownloadTaskStore taskStore = new RemoteDownloadTaskStore(properties);
        PendingFileDecisionRepository pendingRepository = new PendingFileDecisionRepository(objectMapper, properties);
        pendingRepository.initialize();
        pendingFileDecisionService = new PendingFileDecisionService(
                pendingRepository,
                storageService,
                temporaryArtifactRegistry,
                List.of(new RemoteDownloadPendingDecisionObserver(taskStore))
        );
        pendingFileDecisionService.restoreRegistrations();
        RemoteDownloadInspectionService inspectionService = new RemoteDownloadInspectionService(
                properties,
                httpClient,
                fileNameResolver,
                retryPolicy
        );
        transferEngine = new RemoteDownloadTransferEngine(
                properties,
                storageService,
                httpClient,
                fileNameResolver,
                retryPolicy,
                temporaryArtifactRegistry,
                pendingFileDecisionService
        );
        ticketService = new RemoteDownloadRequestTicketService();
        remoteDownloadService = new RemoteDownloadService(
                properties,
                storageService,
                activityLogService,
                new ClientIpResolver(properties),
                new RemoteDownloadRequestParser(validator),
                inspectionService,
                ticketService,
                transferEngine,
                taskStore
        );
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (remoteDownloadService != null) {
            remoteDownloadService.shutdown();
        }
        if (transferEngine != null) {
            transferEngine.shutdown();
        }
    }

    @Test
    void downloadsRemoteFileThroughOneTimeInspectionTicket() throws Exception {
        byte[] body = "remote video".getBytes(StandardCharsets.UTF_8);
        startServer("/media/video.mp4", exchange -> normalResponse(
                exchange,
                body,
                "attachment; filename=\"downloaded.mp4\"",
                "application/octet-stream"
        ));
        Files.createDirectories(root.resolve("incoming"));

        MockHttpServletRequest request = request();
        RemoteDownloadInspection inspection = inspect(
                serverUrl("/media/video.mp4"),
                "incoming",
                NetworkRoute.DIRECT,
                1,
                request
        );
        RemoteDownloadTask task = remoteDownloadService.start(inspection.requestId(), request);
        waitUntilFinished(task);

        assertThat(task.status()).isEqualTo(RemoteDownloadStatus.COMPLETE);
        assertThat(task.targetPath()).isEqualTo("incoming/downloaded.mp4");
        assertThat(root.resolve("incoming/downloaded.mp4")).hasBinaryContent(body);
        assertThat(temporaryArtifactRegistry.activeArtifacts()).isEmpty();
        assertThatThrownBy(() -> remoteDownloadService.start(inspection.requestId(), request))
                .hasMessageContaining("not found or expired");
    }

    @Test
    void inspectUsesGetRangeAndMarksIgnoredRangeAsUnsupported() throws Exception {
        byte[] body = "remote video".getBytes(StandardCharsets.UTF_8);
        AtomicInteger headRequests = new AtomicInteger();
        startServer("/s/token/preview", exchange -> {
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                headRequests.incrementAndGet();
            }
            normalResponse(exchange, body, null, "video/mp4");
        });
        Files.createDirectories(root.resolve("incoming"));

        RemoteDownloadInspection inspection = inspect(
                serverUrl("/s/token/preview"),
                "incoming",
                NetworkRoute.DIRECT,
                1,
                request()
        );

        assertThat(inspection.probe().fileName()).isEqualTo("preview.mp4");
        assertThat(inspection.probe().contentLength()).isEqualTo(body.length);
        assertThat(inspection.probe().rangeCapability()).isEqualTo(RemoteDownloadRangeCapability.UNSUPPORTED);
        assertThat(headRequests).hasValue(0);
    }

    @Test
    void inspectFallsBackToPlainGetWhenRangeRequestIsRejected() throws Exception {
        byte[] body = "plain get only".getBytes(StandardCharsets.UTF_8);
        AtomicInteger plainGets = new AtomicInteger();
        startServer("/files/plain.bin", exchange -> {
            if (exchange.getRequestHeaders().getFirst("Range") != null) {
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
                return;
            }
            plainGets.incrementAndGet();
            normalResponse(exchange, body, null, "application/octet-stream");
        });

        RemoteDownloadInspection inspection = inspect(
                serverUrl("/files/plain.bin"),
                "",
                NetworkRoute.DIRECT,
                1,
                request()
        );

        assertThat(inspection.probe().status()).isEqualTo(RemoteDownloadProbeStatus.LIMITED);
        assertThat(inspection.probe().rangeCapability()).isEqualTo(RemoteDownloadRangeCapability.UNSUPPORTED);
        assertThat(plainGets).hasValue(1);
    }

    @Test
    void retriesSingleConnectionWithoutChangingItsMode() throws Exception {
        byte[] body = "single retry".getBytes(StandardCharsets.UTF_8);
        AtomicInteger fullGets = new AtomicInteger();
        startServer("/files/single-retry.txt", exchange -> {
            if (exchange.getRequestHeaders().getFirst("Range") != null) {
                normalResponse(exchange, body, null, "text/plain");
                return;
            }
            if (fullGets.getAndIncrement() == 0) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            normalResponse(exchange, body, null, "text/plain");
        });

        MockHttpServletRequest request = request();
        RemoteDownloadInspection inspection = inspect(
                serverUrl("/files/single-retry.txt"),
                "",
                NetworkRoute.DIRECT,
                1,
                request
        );
        RemoteDownloadTask task = remoteDownloadService.start(inspection.requestId(), request);
        waitUntilFinished(task);

        assertThat(task.status()).isEqualTo(RemoteDownloadStatus.COMPLETE);
        assertThat(task.actualConnections()).isEqualTo(1);
        assertThat(task.retryCount()).isEqualTo(1);
        assertThat(root.resolve("single-retry.txt")).hasBinaryContent(body);
    }

    @Test
    void keepsSensitiveCustomHeadersInTheServerSidePreparedRequest() throws Exception {
        byte[] body = "authorized".getBytes(StandardCharsets.UTF_8);
        startServer("/private/file.txt", exchange -> {
            if (!"Bearer secret".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            normalResponse(exchange, body, null, "text/plain");
        });

        MockHttpServletRequest request = request();
        RemoteDownloadInspection inspection = remoteDownloadService.inspect(
                serverUrl("/private/file.txt"),
                "",
                NetworkRoute.DIRECT,
                1,
                false,
                "Authorization: Bearer secret",
                request
        );
        RemoteDownloadTask task = remoteDownloadService.start(inspection.requestId(), request);
        waitUntilFinished(task);

        assertThat(task.status()).isEqualTo(RemoteDownloadStatus.COMPLETE);
        assertThat(task.sourceUrl()).doesNotContain("secret");
        assertThat(root.resolve("file.txt")).hasBinaryContent(body);
    }

    @Test
    void inspectionTicketCannotBeConsumedByAnotherSession() throws Exception {
        byte[] body = "session bound".getBytes(StandardCharsets.UTF_8);
        startServer("/files/session.txt", exchange -> normalResponse(
                exchange,
                body,
                null,
                "text/plain"
        ));
        MockHttpServletRequest owner = request();
        RemoteDownloadInspection inspection = inspect(
                serverUrl("/files/session.txt"),
                "",
                NetworkRoute.DIRECT,
                1,
                owner
        );

        assertThatThrownBy(() -> remoteDownloadService.start(inspection.requestId(), request()))
                .hasMessageContaining("another session");

        RemoteDownloadTask task = remoteDownloadService.start(inspection.requestId(), owner);
        waitUntilFinished(task);
        assertThat(task.status()).isEqualTo(RemoteDownloadStatus.COMPLETE);
    }

    @Test
    void downloadsSupportedRangesInParallel() throws Exception {
        byte[] body = new byte[4096];
        for (int index = 0; index < body.length; index++) {
            body[index] = (byte) (index % 251);
        }
        startServer("/files/ranged.bin", exchange -> rangeResponse(exchange, body, null));

        MockHttpServletRequest request = request();
        RemoteDownloadInspection inspection = inspect(
                serverUrl("/files/ranged.bin"),
                "",
                NetworkRoute.DIRECT,
                4,
                request
        );
        assertThat(inspection.probe().rangeCapability()).isEqualTo(RemoteDownloadRangeCapability.SUPPORTED);

        RemoteDownloadTask task = remoteDownloadService.start(inspection.requestId(), request);
        waitUntilFinished(task);

        assertThat(task.status()).isEqualTo(RemoteDownloadStatus.COMPLETE);
        assertThat(task.actualConnections()).isEqualTo(4);
        assertThat(root.resolve("ranged.bin")).hasBinaryContent(body);
    }

    @Test
    void retriesFailedParallelSegmentWithoutChangingDownloadMode() throws Exception {
        byte[] body = "parallel retry payload".repeat(256).getBytes(StandardCharsets.UTF_8);
        AtomicInteger failures = new AtomicInteger();
        startServer("/files/retry.bin", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null && !"bytes=0-0".equals(range) && failures.getAndIncrement() == 0) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            rangeResponse(exchange, body, null);
        });

        MockHttpServletRequest request = request();
        RemoteDownloadInspection inspection = inspect(
                serverUrl("/files/retry.bin"),
                "",
                NetworkRoute.DIRECT,
                2,
                request
        );
        RemoteDownloadTask task = remoteDownloadService.start(inspection.requestId(), request);
        waitUntilFinished(task);

        assertThat(task.status()).isEqualTo(RemoteDownloadStatus.COMPLETE);
        assertThat(task.actualConnections()).isEqualTo(2);
        assertThat(task.retryCount()).isGreaterThanOrEqualTo(1);
        assertThat(root.resolve("retry.bin")).hasBinaryContent(body);
    }

    @Test
    void doesNotFallBackToSingleConnectionWhenRangeStopsWorking() throws Exception {
        byte[] body = "range changed".repeat(256).getBytes(StandardCharsets.UTF_8);
        startServer("/files/changed.bin", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            if ("bytes=0-0".equals(range)) {
                rangeResponse(exchange, body, null);
            } else {
                normalResponse(exchange, body, null, "application/octet-stream");
            }
        });

        MockHttpServletRequest request = request();
        RemoteDownloadInspection inspection = inspect(
                serverUrl("/files/changed.bin"),
                "",
                NetworkRoute.DIRECT,
                2,
                request
        );
        RemoteDownloadTask task = remoteDownloadService.start(inspection.requestId(), request);
        waitUntilFinished(task);

        assertThat(task.status()).isEqualTo(RemoteDownloadStatus.FAILED);
        assertThat(task.message()).contains("stopped honoring Range");
        assertThat(root.resolve("changed.bin")).doesNotExist();
    }

    @Test
    void retainsExistingDestinationAndQueuesDownloadedFileForReview() throws Exception {
        byte[] original = "existing content".getBytes(StandardCharsets.UTF_8);
        byte[] remote = "remote content".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("note.txt"), original);
        startServer("/files/note.txt", exchange -> normalResponse(
                exchange,
                remote,
                null,
                "application/octet-stream"
        ));

        MockHttpServletRequest request = request();
        RemoteDownloadInspection inspection = inspect(
                serverUrl("/files/note.txt"),
                "",
                NetworkRoute.DIRECT,
                1,
                request
        );
        RemoteDownloadTask task = remoteDownloadService.start(inspection.requestId(), request);
        waitUntilFinished(task);

        assertThat(task.status()).isEqualTo(RemoteDownloadStatus.PENDING);
        assertThat(task.pendingDecisionId()).isNotBlank();
        assertThat(root.resolve("note.txt")).hasBinaryContent(original);
        assertThat(pendingFileDecisionService.list()).hasSize(1);
        pendingFileDecisionService.resolve(
                task.pendingDecisionId(),
                PendingFileDecisionAction.KEEP_BOTH,
                null,
                false
        );
        assertThat(task.status()).isEqualTo(RemoteDownloadStatus.COMPLETE);
        assertThat(task.targetPath()).isEqualTo("note - 1.txt");
        assertThat(root.resolve("note.txt")).hasBinaryContent(original);
        assertThat(root.resolve("note - 1.txt")).hasBinaryContent(remote);
        assertThat(pendingFileDecisionService.list()).isEmpty();
        assertThat(temporaryArtifactRegistry.activeArtifacts()).isEmpty();
    }

    @Test
    void skipsTheProbeRequestAndDownloadsWithOneConnection() throws Exception {
        byte[] body = "no preflight".getBytes(StandardCharsets.UTF_8);
        AtomicInteger requests = new AtomicInteger();
        startServer("/files/no-probe.txt", exchange -> {
            requests.incrementAndGet();
            normalResponse(exchange, body, null, "text/plain");
        });

        MockHttpServletRequest request = request();
        RemoteDownloadInspection inspection = remoteDownloadService.inspect(
                serverUrl("/files/no-probe.txt"),
                "",
                NetworkRoute.DIRECT,
                1,
                true,
                "",
                request
        );

        assertThat(requests).hasValue(0);
        assertThat(ticketService.pendingCount()).isEqualTo(1);
        assertThat(inspection.probe().status()).isEqualTo(RemoteDownloadProbeStatus.SKIPPED);
        assertThat(inspection.probe().inspectionSkipped()).isTrue();

        RemoteDownloadTask task = remoteDownloadService.start(inspection.requestId(), request);
        waitUntilFinished(task);

        assertThat(requests).hasValue(1);
        assertThat(task.status()).isEqualTo(RemoteDownloadStatus.COMPLETE);
        assertThat(task.actualConnections()).isEqualTo(1);
        assertThat(root.resolve("no-probe.txt")).hasBinaryContent(body);
    }

    @Test
    void discardsSkippedInspectionTicketWithoutStartingDownload() throws Exception {
        MockHttpServletRequest request = request();
        RemoteDownloadInspection inspection = remoteDownloadService.inspect(
                "https://example.com/no-probe.txt",
                "",
                NetworkRoute.DIRECT,
                1,
                true,
                "",
                request
        );

        assertThat(ticketService.pendingCount()).isEqualTo(1);
        assertThat(remoteDownloadService.discardInspection(inspection.requestId(), request)).isTrue();
        assertThat(remoteDownloadService.discardInspection(inspection.requestId(), request)).isFalse();
        assertThat(ticketService.pendingCount()).isZero();
        assertThatThrownBy(() -> remoteDownloadService.start(inspection.requestId(), request))
                .hasMessageContaining("not found or expired");
    }

    @Test
    void rejectsInspectionDiscardFromAnotherSession() throws Exception {
        MockHttpServletRequest owner = request();
        RemoteDownloadInspection inspection = remoteDownloadService.inspect(
                "https://example.com/no-probe.txt",
                "",
                NetworkRoute.DIRECT,
                1,
                true,
                "",
                owner
        );

        assertThatThrownBy(() -> remoteDownloadService.discardInspection(inspection.requestId(), request()))
                .hasMessageContaining("another session");
        assertThat(ticketService.pendingCount()).isEqualTo(1);
        assertThat(remoteDownloadService.discardInspection(inspection.requestId(), owner)).isTrue();
    }

    @Test
    void preservesLiteralPlusSignsWhenResolvingUrlFileName() {
        RemoteDownloadFileNameResolver resolver = new RemoteDownloadFileNameResolver();

        assertThat(resolver.fileName(URI.create("https://example.com/C++Guide%20One.pdf"), "application/pdf"))
                .isEqualTo("C++Guide One.pdf");
    }

    @Test
    void explicitDirectRouteOverridesCurrentGlobalVpnSelection() throws Exception {
        byte[] body = "direct override".getBytes(StandardCharsets.UTF_8);
        startServer("/files/direct.txt", exchange -> normalResponse(
                exchange,
                body,
                null,
                "application/octet-stream"
        ));
        outboundRouteStateService.changeRoute(NetworkRoute.VPN_REQUIRED);

        MockHttpServletRequest request = request();
        RemoteDownloadInspection inspection = inspect(
                serverUrl("/files/direct.txt"),
                "",
                NetworkRoute.DIRECT,
                1,
                request
        );
        RemoteDownloadTask task = remoteDownloadService.start(inspection.requestId(), request);
        waitUntilFinished(task);

        assertThat(task.status()).isEqualTo(RemoteDownloadStatus.COMPLETE);
        assertThat(task.networkRoute()).isEqualTo(NetworkRoute.DIRECT);
    }

    @Test
    void rejectsVpnRequiredInspectionWhenVpnRouteIsUnavailable() {
        outboundRouteStateService.changeRoute(NetworkRoute.VPN_REQUIRED);

        assertThatThrownBy(() -> inspect(
                "https://example.com/file.bin",
                "",
                NetworkRoute.VPN_REQUIRED,
                1,
                request()
        ))
                .isInstanceOf(OutboundRouteUnavailableException.class)
                .hasMessageContaining("VPN required");
        assertThat(remoteDownloadService.listTasks()).isEmpty();
    }

    private RemoteDownloadInspection inspect(
            String url,
            String path,
            NetworkRoute route,
            int connections,
            MockHttpServletRequest request
    ) throws IOException {
        return remoteDownloadService.inspect(
                url,
                path,
                route,
                connections,
                false,
                "",
                request
        );
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession();
        request.setUserPrincipal(() -> "admin");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private void startServer(String path, ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } catch (IOException ignored) {
                exchange.close();
            }
        });
        server.start();
    }

    private void normalResponse(
            HttpExchange exchange,
            byte[] body,
            String contentDisposition,
            String contentType
    ) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (contentDisposition != null) {
            exchange.getResponseHeaders().set("Content-Disposition", contentDisposition);
        }
        exchange.sendResponseHeaders(200, body.length);
        writeBody(exchange, body, 0, body.length);
    }

    private void rangeResponse(HttpExchange exchange, byte[] body, String contentDisposition) throws IOException {
        String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
        if (rangeHeader == null) {
            normalResponse(exchange, body, contentDisposition, "application/octet-stream");
            return;
        }
        Matcher matcher = RANGE.matcher(rangeHeader);
        if (!matcher.matches()) {
            exchange.sendResponseHeaders(416, -1);
            exchange.close();
            return;
        }
        int start = Integer.parseInt(matcher.group(1));
        int end = Integer.parseInt(matcher.group(2));
        if (start < 0 || end < start || end >= body.length) {
            exchange.getResponseHeaders().set("Content-Range", "bytes */" + body.length);
            exchange.sendResponseHeaders(416, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + body.length);
        exchange.getResponseHeaders().set("ETag", "\"test-resource\"");
        if (contentDisposition != null) {
            exchange.getResponseHeaders().set("Content-Disposition", contentDisposition);
        }
        int length = end - start + 1;
        exchange.sendResponseHeaders(206, length);
        writeBody(exchange, body, start, length);
    }

    private void writeBody(HttpExchange exchange, byte[] body, int offset, int length) throws IOException {
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(body, offset, length);
        }
    }

    private String serverUrl(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private void waitUntilFinished(RemoteDownloadTask task) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (task.active() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L);
        }
        assertThat(task.active()).isFalse();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
