package io.github.fourilla.endervault.remote;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.storage.StorageService;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

class RemoteDownloadServiceTest {

    @TempDir
    Path root;

    private HttpServer server;
    private RemoteDownloadService remoteDownloadService;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        properties.getRemoteDownload().setBlockPrivateNetworks(false);
        properties.getRemoteDownload().setAllowedPorts(List.of());
        properties.getRemoteDownload().setWorkerThreads(1);

        StorageService storageService = new StorageService(properties);
        storageService.initialize();
        ActivityLogService activityLogService = new ActivityLogService(new ObjectMapper().findAndRegisterModules(), properties);
        activityLogService.initialize();
        RemoteDownloadValidator validator = new RemoteDownloadValidator(properties);
        remoteDownloadService = new RemoteDownloadService(
                properties,
                storageService,
                activityLogService,
                validator
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
    }

    @Test
    void downloadsRemoteFileToVaultDirectory() throws Exception {
        byte[] body = "remote video".getBytes(StandardCharsets.UTF_8);
        startServer("/media/video.mp4", body, "attachment; filename=\"downloaded.mp4\"");

        Files.createDirectories(root.resolve("incoming"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setUserPrincipal(() -> "admin");
        request.setRemoteAddr("127.0.0.1");

        RemoteDownloadTask task = remoteDownloadService.start(serverUrl("/media/video.mp4"), "incoming", request);
        waitUntilFinished(task);

        assertThat(task.status()).isEqualTo(RemoteDownloadStatus.COMPLETE);
        assertThat(task.targetPath()).isEqualTo("incoming/downloaded.mp4");
        assertThat(root.resolve("incoming").resolve("downloaded.mp4")).hasBinaryContent(body);
    }

    @Test
    void fallsBackToUrlPathFileName() throws Exception {
        byte[] body = "remote text".getBytes(StandardCharsets.UTF_8);
        startServer("/files/note.txt", body, null);

        MockHttpServletRequest request = new MockHttpServletRequest();
        RemoteDownloadTask task = remoteDownloadService.start(serverUrl("/files/note.txt"), "", request);
        waitUntilFinished(task);

        assertThat(task.status()).isEqualTo(RemoteDownloadStatus.COMPLETE);
        assertThat(root.resolve("note.txt")).hasBinaryContent(body);
    }

    @Test
    void inspectsRemoteFileBeforeStartingDownload() throws Exception {
        byte[] body = "remote video".getBytes(StandardCharsets.UTF_8);
        startServer("/s/token/preview", body, null, "video/mp4");

        Files.createDirectories(root.resolve("incoming"));
        RemoteDownloadProbe probe = remoteDownloadService.inspect(serverUrl("/s/token/preview"), "incoming");

        assertThat(probe.fileName()).isEqualTo("preview.mp4");
        assertThat(probe.targetPath()).isEqualTo("incoming/preview.mp4");
        assertThat(probe.contentType()).isEqualTo("video/mp4");
        assertThat(probe.contentLength()).isEqualTo(body.length);
    }

    @Test
    void completedTasksCanBeRemovedFromHistory() throws Exception {
        byte[] body = "remote text".getBytes(StandardCharsets.UTF_8);
        startServer("/files/delete-me.txt", body, null);

        RemoteDownloadTask task = remoteDownloadService.start(serverUrl("/files/delete-me.txt"), "", new MockHttpServletRequest());
        waitUntilFinished(task);

        remoteDownloadService.deleteTask(task.id());

        assertThat(remoteDownloadService.listTasks()).isEmpty();
    }

    private void startServer(String path, byte[] body, String contentDisposition) throws IOException {
        startServer(path, body, contentDisposition, "application/octet-stream");
    }

    private void startServer(String path, byte[] body, String contentDisposition, String contentType) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.getResponseHeaders().add("Content-Length", String.valueOf(body.length));
            if (contentDisposition != null) {
                exchange.getResponseHeaders().add("Content-Disposition", contentDisposition);
            }
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(body);
            }
        });
        server.start();
    }

    private String serverUrl(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private void waitUntilFinished(RemoteDownloadTask task) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (task.active() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
        assertThat(task.active()).isFalse();
    }
}
