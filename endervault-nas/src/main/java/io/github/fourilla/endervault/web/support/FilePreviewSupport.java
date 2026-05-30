package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.recent.RecentListItem;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.FileItem;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class FilePreviewSupport {

    public boolean previewable(FileItem item) {
        return item != null && !item.directory() && (item.previewable() || comic(item.name()));
    }

    public boolean previewable(RecentListItem item) {
        return item != null && !item.directory() && (item.previewable() || comic(item.name()));
    }

    public boolean previewable(FileDetail detail) {
        return detail != null && !detail.directory() && (detail.previewable() || comic(detail.name()));
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

    public String openUrl(FileItem item) {
        return previewable(item) ? previewUrl(item) : downloadUrl(item.path());
    }

    public String openUrl(RecentListItem item) {
        return previewable(item) ? previewUrl(item) : downloadUrl(item.path());
    }

    private String previewUrl(String name, String path) {
        String endpoint = comic(name) ? "/files/detail/comic/preview" : "/files/detail/preview";
        return UriComponentsBuilder.fromPath(endpoint)
                .queryParam("path", path)
                .build()
                .encode()
                .toUriString();
    }

    private String downloadUrl(String path) {
        return UriComponentsBuilder.fromPath("/files/detail/download")
                .queryParam("path", path)
                .build()
                .encode()
                .toUriString();
    }

    private boolean comic(String name) {
        return extension(name).equals("cbz");
    }

    private String extension(String name) {
        if (name == null) {
            return "";
        }
        int index = name.lastIndexOf('.');
        if (index <= 0 || index == name.length() - 1) {
            return "";
        }
        return name.substring(index + 1).toLowerCase(Locale.ROOT);
    }
}
