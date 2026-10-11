package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.filetool.FileActionKind;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.recent.RecentListItem;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.FileItem;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

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

    public String previewUrl(FileItem item) {
        return filePreviewSupport.previewUrl(item);
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
        return fileHref(kind, item.path(), () -> filePreviewSupport.previewUrl(item));
    }

    private String browserHref(FileActionKind kind, RecentListItem item) {
        return fileHref(kind, item.path(), () -> filePreviewSupport.previewUrl(item));
    }

    private String detailFileHref(FileActionKind kind, FileDetail detail) {
        return fileHref(kind, detail.path(), () -> filePreviewSupport.previewUrl(detail));
    }

    private String fileHref(FileActionKind kind, String path, Supplier<String> previewUrl) {
        return switch (kind) {
            case DETAILS -> InternalUrlBuilder.fromPath("/files/detail").queryParam("path", path).build();
            case DOWNLOAD -> InternalUrlBuilder.fromPath("/files/detail/download").queryParam("path", path).build();
            case PREVIEW -> previewUrl.get();
        };
    }

    @FunctionalInterface
    private interface HrefFactory {
        String href(FileActionKind kind);
    }
}
