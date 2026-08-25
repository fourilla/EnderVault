package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.filetool.FileToolDescriptor;
import io.github.fourilla.endervault.filetool.FileToolService;
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
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AdminFileDetailController {

    private final StorageService storageService;
    private final ShareLinkService shareLinkService;
    private final FavoriteService favoriteService;
    private final FileToolService fileToolService;
    private final TextFileService textFileService;
    private final ComicArchiveService comicArchiveService;
    private final RecentService recentService;
    private final TransferBufferService transferBufferService;
    private final ShareUrlBuilder shareUrlBuilder;

    public AdminFileDetailController(
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

    @GetMapping("/files/detail")
    public String detail(
            @RequestParam("path") String path,
            @RequestParam(value = "comicPage", required = false) Integer comicPage,
            HttpServletRequest request,
            Model model
    ) throws IOException {
        FileDetail detail = detailForPath(path);
        recentService.recordVaultPath(detail.path());
        FileToolDescriptor fileTool = fileToolService.resolve(detail);
        model.addAttribute("detail", detail);
        model.addAttribute("fileTool", fileTool);
        if (fileTool.text()) {
            model.addAttribute(
                    "textContent",
                    textFileService.readText(detail, storageService.resolveVaultFile(detail.path()))
            );
        }
        if (fileTool.comic()) {
            ComicArchiveManifest comicManifest = comicArchiveService.manifest(
                    storageService.resolveVaultFile(detail.path())
            );
            int comicPageIndex = comicArchiveService.normalizePage(comicManifest, comicPage);
            int comicPageNumber = comicManifest.empty() ? 0 : comicPageIndex + 1;
            model.addAttribute("comicManifest", comicManifest);
            model.addAttribute("comicPageIndex", comicPageIndex);
            model.addAttribute("comicPageNumber", comicPageNumber);
            model.addAttribute("comicPreviousPageNumber", Math.max(1, comicPageNumber - 1));
            model.addAttribute("comicNextPageNumber", Math.min(comicManifest.pageCount(), comicPageNumber + 1));
        }
        if (fileTool.archive()) {
            ArchiveFormat archiveFormat = ArchiveFormat.fromFilename(detail.name()).orElseThrow();
            model.addAttribute("archiveSuggestedName", archiveFormat.suggestedDirectoryName(detail.name()));
            model.addAttribute("archiveDestinationPath", detail.parentPath());
        }
        String shareBaseUrl = shareUrlBuilder.shareBaseUrl();
        model.addAttribute("shares", shareLinkService.listForVaultPath(detail.path())
                .stream()
                .map(shareLink -> ShareLinkView.from(shareLink, shareBaseUrl, shareUrlBuilder.directDownloadLinkEnabled()))
                .toList());
        model.addAttribute("favorite", favoriteService.isFavorite(detail.path()));
        model.addAttribute("transferBuffer", transferBufferService.current(request.getSession(false)));
        return "file-detail";
    }

    private FileDetail detailForPath(String path) throws IOException {
        if (path == null || path.isBlank() || "/".equals(path)) {
            throw new NoSuchFileException("");
        }
        return storageService.detail(StorageScope.VAULT, path);
    }
}
