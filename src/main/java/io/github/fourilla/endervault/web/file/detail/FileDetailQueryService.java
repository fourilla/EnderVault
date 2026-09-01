package io.github.fourilla.endervault.web.file.detail;

import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.filetool.FileToolDescriptor;
import io.github.fourilla.endervault.filetool.FileToolService;
import io.github.fourilla.endervault.filetool.TextFileContent;
import io.github.fourilla.endervault.filetool.archive.ArchiveFormat;
import io.github.fourilla.endervault.filetool.comic.ComicArchiveManifest;
import io.github.fourilla.endervault.filetool.comic.ComicArchiveService;
import io.github.fourilla.endervault.filetool.text.TextFileService;
import io.github.fourilla.endervault.recent.RecentService;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.transfer.TransferBufferService;
import io.github.fourilla.endervault.web.support.ShareLinkView;
import io.github.fourilla.endervault.web.support.ShareUrlBuilder;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class FileDetailQueryService {

    private final StorageService storageService;
    private final ShareLinkService shareLinkService;
    private final FavoriteService favoriteService;
    private final FileToolService fileToolService;
    private final TextFileService textFileService;
    private final ComicArchiveService comicArchiveService;
    private final RecentService recentService;
    private final TransferBufferService transferBufferService;
    private final ShareUrlBuilder shareUrlBuilder;

    public FileDetailQueryService(
            StorageService storageService,
            ShareLinkService shareLinkService,
            FavoriteService favoriteService,
            FileToolService fileToolService,
            TextFileService textFileService,
            ComicArchiveService comicArchiveService,
            RecentService recentService,
            TransferBufferService transferBufferService,
            ShareUrlBuilder shareUrlBuilder
    ) {
        this.storageService = storageService;
        this.shareLinkService = shareLinkService;
        this.favoriteService = favoriteService;
        this.fileToolService = fileToolService;
        this.textFileService = textFileService;
        this.comicArchiveService = comicArchiveService;
        this.recentService = recentService;
        this.transferBufferService = transferBufferService;
        this.shareUrlBuilder = shareUrlBuilder;
    }

    public FileDetailResult query(String path, Integer requestedComicPage, HttpServletRequest request) throws IOException {
        FileDetail detail = detailForPath(path);
        recentService.recordVaultPath(detail.path());
        FileToolDescriptor fileTool = fileToolService.resolve(detail);

        TextFileContent textContent = null;
        if (fileTool.text()) {
            textContent = textFileService.readText(detail, storageService.resolveVaultFile(detail.path()));
        }

        ComicArchiveManifest comicManifest = null;
        int comicPageIndex = 0;
        int comicPageNumber = 0;
        int comicPreviousPageNumber = 0;
        int comicNextPageNumber = 0;
        if (fileTool.comic()) {
            comicManifest = comicArchiveService.manifest(storageService.resolveVaultFile(detail.path()));
            comicPageIndex = comicArchiveService.normalizePage(comicManifest, requestedComicPage);
            comicPageNumber = comicManifest.empty() ? 0 : comicPageIndex + 1;
            comicPreviousPageNumber = Math.max(1, comicPageNumber - 1);
            comicNextPageNumber = Math.min(comicManifest.pageCount(), comicPageNumber + 1);
        }

        String archiveSuggestedName = null;
        String archiveDestinationPath = null;
        if (fileTool.archive()) {
            ArchiveFormat archiveFormat = ArchiveFormat.fromFilename(detail.name()).orElseThrow();
            archiveSuggestedName = archiveFormat.suggestedDirectoryName(detail.name());
            archiveDestinationPath = detail.parentPath();
        }

        String shareBaseUrl = shareUrlBuilder.shareBaseUrl();
        List<ShareLinkView> shares = shareLinkService.listForVaultPath(detail.path())
                .stream()
                .map(shareLink -> ShareLinkView.from(
                        shareLink,
                        shareBaseUrl,
                        shareUrlBuilder.directDownloadLinkEnabled()
                ))
                .toList();

        return new FileDetailResult(
                detail,
                fileTool,
                textContent,
                comicManifest,
                comicPageIndex,
                comicPageNumber,
                comicPreviousPageNumber,
                comicNextPageNumber,
                archiveSuggestedName,
                archiveDestinationPath,
                shares,
                favoriteService.isFavorite(detail.path()),
                transferBufferService.current(request.getSession(false))
        );
    }

    private FileDetail detailForPath(String path) throws IOException {
        if (path == null || path.isBlank() || "/".equals(path)) {
            throw new NoSuchFileException("");
        }
        return storageService.detail(StorageScope.VAULT, path);
    }
}
