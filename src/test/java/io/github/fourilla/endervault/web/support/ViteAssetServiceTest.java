package io.github.fourilla.endervault.web.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

class ViteAssetServiceTest {

    @Test
    void resolvesProductionAdminAppEntryFromGeneratedManifest() {
        ViteAssetService service = new ViteAssetService(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                "");

        ViteAssetService.ViteEntry entry = service.entry("src/app/main.tsx");

        assertThat(entry.available()).isTrue();
        assertThat(entry.development()).isFalse();
        assertThat(entry.entryScript()).startsWith("/react/assets/adminApp-").endsWith(".js");
        assertThat(entry.styles())
                .anyMatch(url -> url.startsWith("/react/assets/adminApp-") && url.endsWith(".css"))
                .anyMatch(url -> url.contains("DialogHost-") && url.endsWith(".css"));
    }

    @Test
    void resolvesStandaloneDialogsWithoutAdminRuntime() {
        ViteAssetService service = new ViteAssetService(
                new ObjectMapper(), new DefaultResourceLoader(), "");
        ViteAssetService.ViteEntry entry = service.entry("src/dialogs/main.tsx");

        assertThat(entry.available()).isTrue();
        assertThat(entry.entryScript()).startsWith("/react/assets/dialogs-").endsWith(".js");
        assertThat(entry.styles()).anyMatch(url -> url.contains("DialogHost-") && url.endsWith(".css"));
        assertThat(entry.modulePreloads()).noneMatch(url -> url.contains("adminApp-") || url.contains("shell-"));
    }

    @Test
    void resolvesBundledGlobalStylesFromGeneratedManifest() {
        ViteAssetService service = new ViteAssetService(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                "");

        ViteAssetService.ViteEntry entry = service.entry("src/styles/main.ts");

        assertThat(entry.available()).isTrue();
        assertThat(entry.entryScript()).startsWith("/react/assets/styles-").endsWith(".js");
        assertThat(entry.styles())
                .singleElement()
                .asString()
                .startsWith("/react/assets/styles-")
                .endsWith(".css");
    }

    @Test
    void resolvesBundledShellRuntimeFromGeneratedManifest() {
        ViteAssetService service = new ViteAssetService(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                "");

        ViteAssetService.ViteEntry entry = service.entry("src/shell/main.ts");

        assertThat(entry.available()).isTrue();
        assertThat(entry.entryScript()).startsWith("/react/assets/shell-").endsWith(".js");
        assertThat(entry.styles()).isEmpty();
    }

    @Test
    void resolvesBundledFileToolsFromGeneratedManifest() {
        ViteAssetService service = new ViteAssetService(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                "");

        ViteAssetService.ViteEntry entry = service.entry("src/file-tools/main.ts");

        assertThat(entry.available()).isTrue();
        assertThat(entry.entryScript()).startsWith("/react/assets/fileTools-").endsWith(".js");
        assertThat(entry.styles())
                .singleElement()
                .asString()
                .startsWith("/react/assets/fileTools-")
                .endsWith(".css");
    }

    @Test
    void resolvesSharedImageEntryWithoutAdminRuntime() {
        ViteAssetService service = new ViteAssetService(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                "");

        ViteAssetService.ViteEntry entry = service.entry("src/shared-file/image.tsx");

        assertThat(entry.available()).isTrue();
        assertThat(entry.entryScript()).startsWith("/react/assets/sharedImage-").endsWith(".js");
        assertThat(entry.modulePreloads()).noneMatch(url -> url.contains("adminApp-") || url.contains("shell-"));
    }

    @Test
    void resolvesBundledMarkdownRendererFromGeneratedManifest() {
        ViteAssetService service = new ViteAssetService(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                "");

        ViteAssetService.ViteEntry entry = service.entry("src/markdown/main.ts");

        assertThat(entry.available()).isTrue();
        assertThat(entry.entryScript()).startsWith("/react/assets/markdown-").endsWith(".js");
        assertThat(entry.styles())
                .singleElement()
                .asString()
                .startsWith("/react/assets/markdown-")
                .endsWith(".css");
    }

    @Test
    void resolvesLoopbackDevelopmentEntryWithoutManifest() {
        ViteAssetService service = new ViteAssetService(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                "http://127.0.0.1:5173/");

        ViteAssetService.ViteEntry entry = service.entry("src/app/main.tsx");

        assertThat(entry.available()).isTrue();
        assertThat(entry.development()).isTrue();
        assertThat(entry.clientScript()).isEqualTo("http://127.0.0.1:5173/@vite/client");
        assertThat(entry.entryScript()).isEqualTo("http://127.0.0.1:5173/src/app/main.tsx");
        assertThat(entry.styles()).isEmpty();
    }

    @Test
    void resolvesSharedComicEntryWithoutAdminRuntime() {
        ViteAssetService service = new ViteAssetService(new ObjectMapper(), new DefaultResourceLoader(), "");
        ViteAssetService.ViteEntry entry = service.entry("src/shared-file/comic.tsx");
        assertThat(entry.available()).isTrue();
        assertThat(entry.entryScript()).startsWith("/react/assets/sharedComic-").endsWith(".js");
        assertThat(entry.modulePreloads()).noneMatch(url -> url.contains("adminApp-") || url.contains("shell-"));
    }

    @Test
    void invalidManifestIsUnavailableAndCanBeReadAgainAfterRepair() {
        AtomicReference<String> manifest = new AtomicReference<>("{not-json");
        DefaultResourceLoader loader = new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return new ByteArrayResource(new byte[0]) {
                    @Override
                    public java.io.InputStream getInputStream() {
                        return new java.io.ByteArrayInputStream(manifest.get().getBytes(StandardCharsets.UTF_8));
                    }
                };
            }
        };
        ViteAssetService service = new ViteAssetService(new ObjectMapper(), loader, "");

        assertThat(service.entry("src/app/main.tsx").available()).isFalse();

        manifest.set("{\"src/app/main.tsx\":{\"file\":\"assets/app.js\",\"isEntry\":true}}");
        assertThat(service.entry("src/app/main.tsx").entryScript()).isEqualTo("/react/assets/app.js");
    }

    @Test
    void rejectsNonLoopbackDevelopmentServer() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ViteAssetService(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                "https://assets.example.com"));
    }
}
