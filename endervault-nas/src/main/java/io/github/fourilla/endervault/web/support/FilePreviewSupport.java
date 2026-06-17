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
        return previewUrl(item.name(), item.path());
    }

    public String previewUrl(RecentListItem item) {
        return previewUrl(item.name(), item.path());
    }

    public String previewUrl(FileDetail detail) {
        return previewUrl(detail.name(), detail.path());
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
        return previewable(item) ? previewUrl(item) : downloadUrl(item.path());
    }

    public String openUrl(RecentListItem item) {
        return previewable(item) ? previewUrl(item) : downloadUrl(item.path());
    }

    private String previewUrl(String name, String path) {
        String endpoint = previewType(name) == FileToolType.COMIC ? "/files/detail/comic/preview" : "/files/detail/preview";
        return UriComponentsBuilder.fromPath(endpoint)
                .queryParam("path", path)
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
}
