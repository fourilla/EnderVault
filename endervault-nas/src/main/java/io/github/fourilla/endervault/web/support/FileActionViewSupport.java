package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.filetool.FileActionKind;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.recent.RecentListItem;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.FileItem;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component("fileActions")
public class FileActionViewSupport {

    private final FileActionRegistry fileActionRegistry;
    private final FilePreviewSupport filePreviewSupport;

    public FileActionViewSupport(FileActionRegistry fileActionRegistry, FilePreviewSupport filePreviewSupport) {
        this.fileActionRegistry = fileActionRegistry;
        this.filePreviewSupport = filePreviewSupport;
    }

    public List<FileActionLink> browserActions(FileItem item) {
        return links(fileActionRegistry.browserActions(item), kind -> browserHref(kind, item), true);
    }

    public List<FileActionLink> recentActions(RecentListItem item) {
        return links(fileActionRegistry.browserActions(item), kind -> browserHref(kind, item), true);
    }

    public List<FileActionLink> searchActions(FileItem item) {
        return browserActions(item);
    }

    public List<FileActionLink> detailFileActions(FileDetail detail) {
        if (detail.directory()) {
            return List.of();
        }
        return links(fileActionRegistry.browserActions(detail), kind -> detailFileHref(kind, detail), true);
    }

    public List<FileActionLink> sharedDirectoryActions(String token, FileItem item) {
        return links(fileActionRegistry.sharedDirectoryActions(item), kind -> sharedDirectoryHref(kind, token, item), false);
    }

    private List<FileActionLink> links(List<FileActionKind> kinds, HrefFactory hrefFactory, boolean previewInNewTab) {
        return kinds.stream()
                .map(kind -> new FileActionLink(
                        kind,
                        kind.id(),
                        kind.label(),
                        kind.icon(),
                        hrefFactory.href(kind),
                        previewInNewTab && kind == FileActionKind.PREVIEW
                ))
                .toList();
    }

    private String browserHref(FileActionKind kind, FileItem item) {
        return switch (kind) {
            case DETAILS -> UriComponentsBuilder.fromPath("/files/detail")
                    .queryParam("path", item.path())
                    .build()
                    .encode()
                    .toUriString();
            case DOWNLOAD -> UriComponentsBuilder.fromPath("/files/detail/download")
                    .queryParam("path", item.path())
                    .build()
                    .encode()
                    .toUriString();
            case PREVIEW -> filePreviewSupport.previewUrl(item);
        };
    }

    private String browserHref(FileActionKind kind, RecentListItem item) {
        return switch (kind) {
            case DETAILS -> UriComponentsBuilder.fromPath("/files/detail")
                    .queryParam("path", item.path())
                    .build()
                    .encode()
                    .toUriString();
            case DOWNLOAD -> UriComponentsBuilder.fromPath("/files/detail/download")
                    .queryParam("path", item.path())
                    .build()
                    .encode()
                    .toUriString();
            case PREVIEW -> filePreviewSupport.previewUrl(item);
        };
    }

    private String detailFileHref(FileActionKind kind, FileDetail detail) {
        return switch (kind) {
            case DETAILS -> UriComponentsBuilder.fromPath("/files/detail")
                    .queryParam("path", detail.path())
                    .build()
                    .encode()
                    .toUriString();
            case DOWNLOAD -> UriComponentsBuilder.fromPath("/files/detail/download")
                    .queryParam("path", detail.path())
                    .build()
                    .encode()
                    .toUriString();
            case PREVIEW -> filePreviewSupport.previewUrl(detail);
        };
    }

    private String sharedDirectoryHref(FileActionKind kind, String token, FileItem item) {
        return switch (kind) {
            case DETAILS -> filePreviewSupport.sharedDirectoryFileUrl(token, item);
            case DOWNLOAD -> filePreviewSupport.sharedDirectoryDownloadUrl(token, item);
            case PREVIEW -> filePreviewSupport.sharedDirectoryFileUrl(token, item);
        };
    }

    @FunctionalInterface
    private interface HrefFactory {
        String href(FileActionKind kind);
    }
}
