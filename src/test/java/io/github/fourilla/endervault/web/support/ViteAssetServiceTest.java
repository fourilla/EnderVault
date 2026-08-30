package io.github.fourilla.endervault.web.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class ViteAssetServiceTest {

    @Test
    void resolvesProductionEntryFromGeneratedManifest() {
        ViteAssetService service = new ViteAssetService(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                "");

        ViteAssetService.ViteEntry entry = service.entry("src/settings/main.tsx");

        assertThat(entry.available()).isTrue();
        assertThat(entry.development()).isFalse();
        assertThat(entry.entryScript()).startsWith("/react/assets/settings-").endsWith(".js");
        assertThat(entry.styles())
                .singleElement()
                .asString()
                .startsWith("/react/assets/settings-")
                .endsWith(".css");
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
    void resolvesBundledAdminAppEntryFromGeneratedManifest() {
        ViteAssetService service = new ViteAssetService(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                "");

        ViteAssetService.ViteEntry entry = service.entry("src/app/main.tsx");

        assertThat(entry.available()).isTrue();
        assertThat(entry.entryScript()).startsWith("/react/assets/adminApp-").endsWith(".js");
        assertThat(entry.styles())
                .singleElement()
                .asString()
                .startsWith("/react/assets/adminApp-")
                .endsWith(".css");
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
    void resolvesBundledBookmarksEntryFromGeneratedManifest() {
        ViteAssetService service = new ViteAssetService(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                "");

        ViteAssetService.ViteEntry entry = service.entry("src/bookmarks/main.tsx");

        assertThat(entry.available()).isTrue();
        assertThat(entry.entryScript()).startsWith("/react/assets/bookmarks-").endsWith(".js");
        assertThat(entry.styles())
                .singleElement()
                .asString()
                .startsWith("/react/assets/bookmarks-")
                .endsWith(".css");
    }

    @Test
    void resolvesBundledFavoritesEntryFromGeneratedManifest() {
        ViteAssetService service = new ViteAssetService(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                "");

        ViteAssetService.ViteEntry entry = service.entry("src/favorites/main.tsx");

        assertThat(entry.available()).isTrue();
        assertThat(entry.entryScript()).startsWith("/react/assets/favorites-").endsWith(".js");
        assertThat(entry.styles())
                .singleElement()
                .asString()
                .startsWith("/react/assets/favorites-")
                .endsWith(".css");
    }

    @Test
    void resolvesBundledTrashEntryFromGeneratedManifest() {
        ViteAssetService service = new ViteAssetService(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                "");

        ViteAssetService.ViteEntry entry = service.entry("src/trash/main.tsx");

        assertThat(entry.available()).isTrue();
        assertThat(entry.entryScript()).startsWith("/react/assets/trash-").endsWith(".js");
        assertThat(entry.styles())
                .singleElement()
                .asString()
                .startsWith("/react/assets/trash-")
                .endsWith(".css");
    }

    @Test
    void resolvesBundledActivityLogsEntryFromGeneratedManifest() {
        ViteAssetService service = new ViteAssetService(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                "");

        ViteAssetService.ViteEntry entry = service.entry("src/activity-logs/main.tsx");

        assertThat(entry.available()).isTrue();
        assertThat(entry.entryScript()).startsWith("/react/assets/activityLogs-").endsWith(".js");
        assertThat(entry.styles())
                .singleElement()
                .asString()
                .startsWith("/react/assets/activityLogs-")
                .endsWith(".css");
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

        ViteAssetService.ViteEntry entry = service.entry("src/settings/main.tsx");

        assertThat(entry.available()).isTrue();
        assertThat(entry.development()).isTrue();
        assertThat(entry.clientScript()).isEqualTo("http://127.0.0.1:5173/@vite/client");
        assertThat(entry.entryScript()).isEqualTo("http://127.0.0.1:5173/src/settings/main.tsx");
        assertThat(entry.styles()).isEmpty();
    }

    @Test
    void rejectsNonLoopbackDevelopmentServer() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ViteAssetService(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                "https://assets.example.com"));
    }
}
