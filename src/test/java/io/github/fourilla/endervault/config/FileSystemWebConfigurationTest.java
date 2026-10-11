package io.github.fourilla.endervault.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.filesystem.FileSystemOperations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class FileSystemWebConfigurationTest {
    @AfterEach
    void restorePolicy() {
        FileSystemOperations.configureNativePolicy(() -> true);
    }

    @Test
    void asyncRedispatchRetainsTheRequestPolicyAndReleasesTheServletThread() {
        var interceptor = new FileSystemWebConfiguration.OperationInterceptor();
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var handler = new Object();
        FileSystemOperations.configureNativePolicy(() -> true);
        try {
            assertThat(interceptor.preHandle(request, response, handler)).isTrue();
            FileSystemOperations.configureNativePolicy(() -> false);
            assertThat(FileSystemOperations.operationOrCapture().nativeEnabled()).isTrue();
            interceptor.afterConcurrentHandlingStarted(request, response, handler);
            assertThat(FileSystemOperations.operationOrCapture().nativeEnabled()).isFalse();
            assertThat(interceptor.preHandle(request, response, handler)).isTrue();
            assertThat(FileSystemOperations.operationOrCapture().nativeEnabled()).isTrue();
            interceptor.afterCompletion(request, response, handler, new IllegalArgumentException("Failed request."));
            assertThat(FileSystemOperations.operationOrCapture().nativeEnabled()).isFalse();
            assertThat(FileSystemOperations.currentDiagnostics()).isEmpty();
        } finally {
            interceptor.afterCompletion(request, response, handler, null);
        }
    }

    @Test
    void nestedDispatchClosesBothScopesWithoutLeakingTheRequestPolicy() {
        var interceptor = new FileSystemWebConfiguration.OperationInterceptor();
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var handler = new Object();
        FileSystemOperations.configureNativePolicy(() -> true);
        try {
            interceptor.preHandle(request, response, handler);
            FileSystemOperations.configureNativePolicy(() -> false);
            interceptor.preHandle(request, response, handler);
            interceptor.afterCompletion(request, response, handler, null);
            assertThat(FileSystemOperations.operationOrCapture().nativeEnabled()).isTrue();
            interceptor.afterCompletion(request, response, handler, null);
            assertThat(FileSystemOperations.operationOrCapture().nativeEnabled()).isFalse();
        } finally {
            interceptor.afterCompletion(request, response, handler, null);
            interceptor.afterCompletion(request, response, handler, null);
        }
    }
}
