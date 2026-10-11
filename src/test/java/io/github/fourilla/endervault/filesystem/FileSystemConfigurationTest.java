package io.github.fourilla.endervault.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.fourilla.endervault.config.FileSystemConfiguration;
import io.github.fourilla.endervault.config.NasProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class FileSystemConfigurationTest {

    @TempDir Path root;

    @BeforeEach
    @AfterEach
    void defaultPolicy() {
        FileSystemOperations.configureNativePolicy(() -> true);
    }

    @Test
    void bootstrapDisablesEarlyQueriesAndTheBoundPolicyRemainsLiveForNewOperations() throws Exception {
        Path source = Files.writeString(root.resolve("source"), "content");
        AtomicInteger loads = new AtomicInteger();
        NativeFileSystem backend = mock(NativeFileSystem.class);
        when(backend.supportsNoReplaceMove(source, root)).thenReturn(true);
        FileSystemOperations earlyOperations = new DefaultFileSystemOperations(new NioFileSystem(), () -> {
            loads.incrementAndGet();
            return backend;
        });
        try (var context = contextWithPolicy("false")) {
            // Registered before the configuration and NasProperties beans: this query must see
            // the resolved startup policy even if the live supplier has not been connected yet.
            context.registerBean("earlyFilesystemQuery", Boolean.class, () -> {
                try {
                    return earlyOperations.supportsNoReplaceMove(source, root);
                } catch (java.io.IOException ex) {
                    throw new IllegalStateException(ex);
                }
            });
            context.registerBean(NasProperties.class, () -> Binder.get(context.getEnvironment())
                    .bind("nas", NasProperties.class).orElseGet(NasProperties::new));
            context.register(FileSystemConfiguration.class);
            context.refresh();

            assertThat(context.getBean("earlyFilesystemQuery", Boolean.class)).isFalse();
            assertThat(loads.get()).isZero();
            assertThat(context.getBean(FileSystemOperations.class)).isSameAs(FileSystemOperations.systemDefault());
            FileSystemOperation admitted = FileSystemOperations.captureOperation();
            assertThat(admitted.nativeEnabled()).isFalse();
            context.getBean(NasProperties.class).getFilesystem().setNativeEnabled(true);
            assertThat(FileSystemOperations.captureOperation().nativeEnabled()).isTrue();
            try (var scope = admitted.activate()) {
                assertThat(earlyOperations.supportsNoReplaceMove(source, root)).isFalse();
            }
            assertThat(loads.get()).isZero();
            assertThat(earlyOperations.supportsNoReplaceMove(source, root)).isTrue();
            assertThat(loads.get()).isOne();
        }
    }

    @Test
    void invalidStartupPolicyIsRejectedBeforeNormalBeansAreCreated() {
        AtomicInteger normalBeans = new AtomicInteger();
        try (var context = contextWithPolicy("automatic")) {
            context.registerBean(NasProperties.class, NasProperties::new);
            context.registerBean("normalBean", String.class, () -> {
                normalBeans.incrementAndGet();
                return "created";
            });
            context.register(FileSystemConfiguration.class);
            assertThatThrownBy(context::refresh).hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThat(normalBeans.get()).isZero();
        }
    }

    private static AnnotationConfigApplicationContext contextWithPolicy(String policy) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("filesystem-policy",
                Map.of("nas.filesystem.native-enabled", policy)));
        return context;
    }
}
