package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.filetool.FileToolType;
import io.github.fourilla.endervault.recent.RecentListItem;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.FileItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class FilePreviewSupport {

    private final FileActionRegistry fileActionRegistry;

    public FilePreviewSupport() {
        this(new FileActionRegistry());
    }

    @Autowired
    public FilePreviewSupport(FileActionRegistry fileActionRegistry) {
        this.fileActionRegistry = fileActionRegistry;
    }

    public boolean previewable(FileItem item) {
        return item != null && fileActionRegistry.previewPageAvailable(item);
    }

    public boolean previewable(RecentListItem item) {
        return item != null && fileActionRegistry.previewPageAvailable(item);
    }

    public boolean previewable(FileDetail detail) {
        return detail != null && fileActionRegistry.previewPageAvailable(detail);
    }

    public String previewUrl(FileItem item) {
        return openUrl(item.path());
    }

    public String previewUrl(RecentListItem item) {
        return openUrl(item.path());
    }

    public String previewUrl(FileDetail detail) {
        return openUrl(detail.path());
    }

    public String cardMediaUrl(FileItem item) {
        return cardMediaUrl(item.name(), item.path(), item.image());
    }

    public String cardMediaUrl(RecentListItem item) {
        return cardMediaUrl(item.name(), item.path(), item.image());
    }

    public String cardMediaUrl(FileDetail detail) {
        return cardMediaUrl(detail.name(), detail.path(), detail.image());
    }

    public String previewContentUrl(FileDetail detail) {
        if (previewType(detail.name()) == FileToolType.COMIC) {
            return UriComponentsBuilder.fromPath("/files/detail/comic/preview")
                    .queryParam("path", detail.path())
                    .build()
                    .encode()
                    .toUriString();
        }
        return fileContentUrl("/files/preview", detail.name(), detail.path());
    }

    public String openTargetUrl(FileDetail detail) {
        return previewable(detail) ? previewContentUrl(detail) : downloadUrl(detail.path());
    }

    public String sharedFilePreviewUrl(String token, FileItem item) {
        return sharedPreviewUrl(token, item.name(), null, null);
    }

    public String sharedDirectoryPreviewUrl(String token, FileItem item) {
        return sharedPreviewUrl(token, item.name(), item.parentPath(), item.name());
    }

    public String sharedDirectoryFileUrl(String token, FileItem item) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/s/{token}/file")
                .queryParam("item", item.name());
        if (item.parentPath() != null && !item.parentPath().isBlank()) {
            builder.queryParam("path", item.parentPath());
        }
        return builder.buildAndExpand(token).encode().toUriString();
    }

    public String sharedFileDownloadUrl(String token, FileItem item) {
        return sharedDownloadUrl(token, item.name(), null, null);
    }

    public String sharedDirectoryDownloadUrl(String token, FileItem item) {
        return sharedDownloadUrl(token, item.name(), item.parentPath(), item.name());
    }

    public String openUrl(FileItem item) {
        return openUrl(item.path());
    }

    public String openUrl(RecentListItem item) {
        return openUrl(item.path());
    }

    private String openUrl(String path) {
        return UriComponentsBuilder.fromPath("/files/open")
                .queryParam("path", path)
                .build()
                .encode()
                .toUriString();
    }

    private String cardMediaUrl(String name, String path, boolean image) {
        return fileContentUrl(image ? "/files/preview" : "/files/thumbnail", name, path);
    }

    private String fileContentUrl(String endpoint, String name, String path) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(endpoint);
        String parentPath = parentPath(path);
        if (!parentPath.isBlank()) {
            builder.queryParam("path", parentPath);
        }
        return builder.queryParam("item", name)
                .build()
                .encode()
                .toUriString();
    }

    private String sharedPreviewUrl(String token, String name, String path, String item) {
        String endpoint = previewType(name) == FileToolType.COMIC ? "/s/{token}/comic/preview" : "/s/{token}/preview";
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(endpoint);
        if (path != null && !path.isBlank()) {
            builder.queryParam("path", path);
        }
        if (item != null && !item.isBlank()) {
            builder.queryParam("item", item);
        }
        return builder.buildAndExpand(token).encode().toUriString();
    }

    private String sharedDownloadUrl(String token, String name, String path, String item) {
        UriComponentsBuilder builder = UriComponentsBuilder.newInstance()
                .pathSegment("s", token, "download", name);
        if (path != null && !path.isBlank()) {
            builder.queryParam("path", path);
        }
        if (item != null && !item.isBlank()) {
            builder.queryParam("item", item);
        }
        return builder.build().encode().toUriString();
    }

    private String downloadUrl(String path) {
        return UriComponentsBuilder.fromPath("/files/detail/download")
                .queryParam("path", path)
                .build()
                .encode()
                .toUriString();
    }

    private FileToolType previewType(String name) {
        return fileActionRegistry.typeFor(name, false, "", fileActionRegistry.extension(name));
    }

    private String parentPath(String path) {
        int index = path == null ? -1 : path.lastIndexOf('/');
        return index < 0 ? "" : path.substring(0, index);
    }
}
