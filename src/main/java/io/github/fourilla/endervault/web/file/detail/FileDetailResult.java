package io.github.fourilla.endervault.web.file.detail;

import io.github.fourilla.endervault.filetool.FileToolDescriptor;
import io.github.fourilla.endervault.filetool.TextFileContent;
import io.github.fourilla.endervault.filetool.comic.ComicArchiveManifest;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.transfer.TransferBuffer;
import io.github.fourilla.endervault.web.support.ShareLinkView;
import java.util.List;

public record FileDetailResult(
        FileDetail detail,
        FileToolDescriptor fileTool,
        TextFileContent textContent,
        ComicArchiveManifest comicManifest,
        int comicPageIndex,
        int comicPageNumber,
        int comicPreviousPageNumber,
        int comicNextPageNumber,
        String archiveSuggestedName,
        String archiveDestinationPath,
        List<ShareLinkView> shares,
        boolean favorite,
        TransferBuffer transferBuffer
) {
}
