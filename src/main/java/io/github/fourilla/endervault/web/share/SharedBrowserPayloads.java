package io.github.fourilla.endervault.web.share;

import java.util.List;

public final class SharedBrowserPayloads {

    private SharedBrowserPayloads() {
    }

    public record Listing(
            String targetType,
            String view,
            String path,
            String parentPath,
            String rootUrl,
            String upUrl,
            List<Breadcrumb> breadcrumbs,
            List<Entry> entries,
            String downloadZipUrl
    ) {
    }

    public record Breadcrumb(String label, String path, String url) {
    }

    public record Entry(
            String name,
            String path,
            boolean directory,
            boolean hidden,
            String sizeLabel,
            String modifiedLabel,
            String mediaType,
            String typeLabel,
            String openUrl,
            String detailUrl,
            String downloadUrl,
            String previewLandingUrl
    ) {
    }

    public record Detail(
            String targetType,
            String view,
            String path,
            String parentPath,
            String name,
            String mediaType,
            String sizeLabel,
            String modifiedLabel,
            String extension,
            String rootUrl,
            String upUrl,
            String downloadUrl,
            String toolType,
            String toolLabel,
            boolean previewEnabled,
            String previewContentUrl,
            String comicManifestUrl,
            Text text
    ) {
    }

    public record Text(boolean loaded, String content, String message) {
    }
}
