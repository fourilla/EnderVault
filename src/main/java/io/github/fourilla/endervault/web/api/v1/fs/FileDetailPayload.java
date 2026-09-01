package io.github.fourilla.endervault.web.api.v1.fs;

import io.github.fourilla.endervault.filetool.FileToolDescriptor;
import io.github.fourilla.endervault.filetool.TextFileContent;
import io.github.fourilla.endervault.filetool.comic.ComicArchiveManifest;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.web.api.v1.file.TransferBufferActionResponse.TransferBufferPayload;
import io.github.fourilla.endervault.web.file.detail.FileDetailResult;
import io.github.fourilla.endervault.web.support.FileActionLink;
import io.github.fourilla.endervault.web.support.FileActionViewSupport;
import io.github.fourilla.endervault.web.support.FilePreviewSupport;
import io.github.fourilla.endervault.web.support.ShareLinkPayload;
import java.util.List;
import org.springframework.web.util.UriComponentsBuilder;

public record FileDetailPayload(
        DetailPayload detail,
        ToolPayload tool,
        ToolUrlsPayload urls,
        TextFileContent text,
        ComicPayload comic,
        ArchivePayload archive,
        List<ShareLinkPayload> shares,
        boolean favorite,
        TransferBufferPayload transferBuffer
) {

    public static FileDetailPayload from(
            FileDetailResult result,
            FilePreviewSupport filePreviewSupport,
            FileActionViewSupport fileActionViewSupport
    ) {
        FileDetail detail = result.detail();
        return new FileDetailPayload(
                DetailPayload.from(detail),
                ToolPayload.from(result.fileTool()),
                ToolUrlsPayload.from(detail, filePreviewSupport, fileActionViewSupport),
                result.textContent(),
                ComicPayload.from(result),
                ArchivePayload.from(result),
                result.shares().stream().map(ShareLinkPayload::from).toList(),
                result.favorite(),
                TransferBufferPayload.from(result.transferBuffer())
        );
    }

    public record DetailPayload(
            String name,
            String path,
            String parentPath,
            boolean directory,
            long size,
            String sizeLabel,
            long childCount,
            String createdLabel,
            String modifiedLabel,
            String accessedLabel,
            String mediaType,
            String extension,
            boolean previewable,
            boolean streamable,
            boolean hidden
    ) {
        static DetailPayload from(FileDetail detail) {
            return new DetailPayload(
                    detail.name(),
                    detail.path(),
                    detail.parentPath(),
                    detail.directory(),
                    detail.size(),
                    detail.sizeLabel(),
                    detail.childCount(),
                    detail.createdLabel(),
                    detail.modifiedLabel(),
                    detail.accessedLabel(),
                    detail.mediaType(),
                    detail.extension(),
                    detail.previewable(),
                    detail.streamable(),
                    detail.hidden()
            );
        }
    }

    public record ToolPayload(
            String type,
            String label,
            boolean inlinePreview,
            boolean previewPage,
            boolean editable,
            boolean markdown
    ) {
        static ToolPayload from(FileToolDescriptor tool) {
            return new ToolPayload(
                    tool.id(),
                    tool.label(),
                    tool.previewable(),
                    tool.previewPageAvailable(),
                    tool.editable(),
                    tool.markdown()
            );
        }
    }

    public record ToolUrlsPayload(
            String parentDirectory,
            String openDirectory,
            String download,
            String downloadZip,
            String cardMedia,
            String previewContent,
            List<ActionPayload> actions
    ) {
        static ToolUrlsPayload from(
                FileDetail detail,
                FilePreviewSupport filePreviewSupport,
                FileActionViewSupport fileActionViewSupport
        ) {
            return new ToolUrlsPayload(
                    filesUrl(detail.parentPath()),
                    detail.directory() ? filesUrl(detail.path()) : null,
                    detailUrl("/files/detail/download", detail.path()),
                    detail.directory() ? detailUrl("/files/detail/download.zip", detail.path()) : null,
                    detail.directory() ? null : filePreviewSupport.cardMediaUrl(detail),
                    detail.directory() ? null : filePreviewSupport.previewContentUrl(detail),
                    fileActionViewSupport.detailFileActions(detail).stream().map(ActionPayload::from).toList()
            );
        }

        private static String filesUrl(String path) {
            UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/files");
            if (path != null && !path.isBlank()) {
                builder.queryParam("path", path);
            }
            return builder.build().encode().toUriString();
        }

        private static String detailUrl(String endpoint, String path) {
            return UriComponentsBuilder.fromPath(endpoint)
                    .queryParam("path", path)
                    .build()
                    .encode()
                    .toUriString();
        }
    }

    public record ActionPayload(String id, String label, String icon, String href, boolean newTab) {
        static ActionPayload from(FileActionLink action) {
            return new ActionPayload(
                    action.id(),
                    action.label(),
                    action.icon(),
                    action.href(),
                    action.newTab()
            );
        }
    }

    public record ComicPayload(
            ComicArchiveManifest manifest,
            int pageIndex,
            int pageNumber,
            int previousPageNumber,
            int nextPageNumber,
            String pageUrl
    ) {
        static ComicPayload from(FileDetailResult result) {
            if (result.comicManifest() == null) {
                return null;
            }
            return new ComicPayload(
                    result.comicManifest(),
                    result.comicPageIndex(),
                    result.comicPageNumber(),
                    result.comicPreviousPageNumber(),
                    result.comicNextPageNumber(),
                    UriComponentsBuilder.fromPath("/files/detail/comic/page")
                            .queryParam("path", result.detail().path())
                            .build()
                            .encode()
                            .toUriString()
            );
        }
    }

    public record ArchivePayload(String suggestedName, String destinationPath, String entriesUrl) {
        static ArchivePayload from(FileDetailResult result) {
            if (result.archiveSuggestedName() == null) {
                return null;
            }
            return new ArchivePayload(
                    result.archiveSuggestedName(),
                    result.archiveDestinationPath(),
                    UriComponentsBuilder.fromPath("/api/v1/fs/archive/entries")
                            .queryParam("path", result.detail().path())
                            .build()
                            .encode()
                            .toUriString()
            );
        }
    }
}
