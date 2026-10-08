package io.github.fourilla.endervault.web.share;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.filetool.FileToolDescriptor;
import io.github.fourilla.endervault.filetool.FileToolService;
import io.github.fourilla.endervault.filetool.TextFileContent;
import io.github.fourilla.endervault.filetool.text.TextFileService;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
import io.github.fourilla.endervault.share.ShareLink;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.share.ShareTargetType;
import io.github.fourilla.endervault.storage.DirectoryListing;
import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.web.support.FilePreviewSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class SharedBrowserQueryService {

    private final ShareLinkService shareLinkService;
    private final StorageService storageService;
    private final FileActionRegistry fileActionRegistry;
    private final FileToolService fileToolService;
    private final FilePreviewSupport filePreviewSupport;
    private final TextFileService textFileService;
    private final SharedPreviewPolicy sharedPreviewPolicy;
    private final ActivityLogService activityLogService;
    private final PublicLinkTokenService publicLinkTokenService;

    public SharedBrowserQueryService(
            ShareLinkService shareLinkService,
            StorageService storageService,
            FileActionRegistry fileActionRegistry,
            FileToolService fileToolService,
            FilePreviewSupport filePreviewSupport,
            TextFileService textFileService,
            SharedPreviewPolicy sharedPreviewPolicy,
            ActivityLogService activityLogService,
            PublicLinkTokenService publicLinkTokenService
    ) {
        this.shareLinkService = shareLinkService;
        this.storageService = storageService;
        this.fileActionRegistry = fileActionRegistry;
        this.fileToolService = fileToolService;
        this.filePreviewSupport = filePreviewSupport;
        this.textFileService = textFileService;
        this.sharedPreviewPolicy = sharedPreviewPolicy;
        this.activityLogService = activityLogService;
        this.publicLinkTokenService = publicLinkTokenService;
    }

    public ShareTargetType validateLanding(String token, String path) throws IOException {
        try {
            ShareLink shareLink = shareLinkService.requireUsable(token);
            if (shareLink.type() == ShareTargetType.FILE) {
                storageService.resolveVaultFile(shareLink.path());
            } else {
                storageService.listSharedDirectory(shareLink.path(), path);
            }
            return shareLink.type();
        } catch (NoSuchFileException exception) {
            // The HTML exception view must not display filesystem paths from failed host validation.
            throw new NoSuchFileException("Shared content is unavailable.");
        } catch (StorageAccessException exception) {
            throw new StorageAccessException("Shared content is unavailable.", exception);
        }
    }

    public ShareTargetType validateFileLanding(String token, String path, String itemName) throws IOException {
        try {
            ShareLink shareLink = shareLinkService.requireUsable(token);
            requireDirectory(shareLink);
            storageService.describeSharedFile(shareLink.path(), path, itemName);
            return shareLink.type();
        } catch (NoSuchFileException exception) {
            throw new NoSuchFileException("Shared content is unavailable.");
        } catch (StorageAccessException exception) {
            throw new StorageAccessException("Shared content is unavailable.", exception);
        }
    }

    public SharedBrowserPayloads.Listing listing(String token, String path, HttpServletRequest request)
            throws IOException {
        ShareLink shareLink = shareLinkService.requireUsable(token);
        requireDirectory(shareLink);
        DirectoryListing listing = storageService.listSharedDirectory(shareLink.path(), path);
        SharedBrowserPayloads.Listing payload = new SharedBrowserPayloads.Listing(
                shareLink.type().name(),
                "listing",
                listing.path(),
                listing.parentPath(),
                SharedFileRoutes.directoryUrl(token, null),
                listing.hasParent() ? SharedFileRoutes.directoryUrl(token, listing.parentPath()) : null,
                listing.breadcrumbs().stream()
                        .map(crumb -> new SharedBrowserPayloads.Breadcrumb(
                                crumb.label(), crumb.path(), SharedFileRoutes.directoryUrl(token, crumb.path())))
                        .toList(),
                Stream.concat(listing.directories().stream(), listing.files().stream())
                        .map(item -> entry(shareLink, item))
                        .toList(),
                SharedFileRoutes.downloadZipUrl(token, listing.path())
        );
        recordAccess(shareLink, request, listing.path(), "Accessed share link");
        return payload;
    }

    public SharedBrowserPayloads.Detail detail(String token, String path, String itemName, HttpServletRequest request)
            throws IOException {
        ShareLink shareLink = shareLinkService.requireUsable(token);
        boolean directoryShare = shareLink.type() == ShareTargetType.DIRECTORY;
        FileItem item;
        String vaultPath;
        if (directoryShare) {
            if (itemName == null || itemName.isBlank()) {
                throw new IllegalArgumentException("A shared item is required.");
            }
            item = storageService.describeSharedFile(shareLink.path(), path, itemName);
            // Build the vault lookup from the already validated, canonical shared-relative identity.
            vaultPath = SharedFileRoutes.itemVaultPath(shareLink.path(), item.parentPath(), item.name());
        } else {
            // Query parameters never widen the capability of a direct FILE token.
            storageService.resolveVaultFile(shareLink.path());
            item = storageService.describeVaultPath(shareLink.path());
            vaultPath = shareLink.path();
        }
        FileDetail detail = storageService.detail(StorageScope.VAULT, vaultPath);
        FileToolDescriptor tool = fileToolService.resolve(detail);
        boolean previewEnabled = sharedPreviewPolicy.isEnabled(shareLink, tool);
        String previewContentUrl = previewEnabled
                ? directoryShare
                        ? filePreviewSupport.sharedDirectoryPreviewUrl(token, item)
                        : filePreviewSupport.sharedFilePreviewUrl(token, item)
                : null;
        String comicManifestUrl = previewEnabled && tool.comic()
                ? SharedFileRoutes.comicManifestUrl(
                        token, directoryShare ? item.parentPath() : null, directoryShare ? item.name() : null)
                : null;
        SharedBrowserPayloads.Text text = null;
        if (previewEnabled && tool.text()) {
            TextFileContent content = textFileService.readText(detail, storageService.resolveVaultFile(vaultPath));
            text = new SharedBrowserPayloads.Text(content.loaded(), content.content(), content.message());
        }
        SharedBrowserPayloads.Detail payload = new SharedBrowserPayloads.Detail(
                shareLink.type().name(),
                "detail",
                directoryShare ? item.path() : "",
                directoryShare ? item.parentPath() : null,
                item.name(),
                detail.mediaType(),
                detail.sizeLabel(),
                detail.modifiedLabel(),
                detail.extension(),
                SharedFileRoutes.directoryUrl(token, null),
                directoryShare ? SharedFileRoutes.directoryUrl(token, item.parentPath()) : null,
                directoryShare
                        ? filePreviewSupport.sharedDirectoryDownloadUrl(token, item)
                        : filePreviewSupport.sharedFileDownloadUrl(token, item),
                tool.id(),
                tool.text() ? "Text Preview" : tool.label(),
                previewEnabled,
                previewContentUrl,
                comicManifestUrl,
                text
        );
        recordAccess(shareLink, request, directoryShare ? item.path() : null,
                directoryShare ? "Accessed shared file " + item.name() : "Accessed share link");
        return payload;
    }

    private SharedBrowserPayloads.Entry entry(ShareLink shareLink, FileItem item) {
        String token = shareLink.token();
        String detailUrl = item.directory() ? null : filePreviewSupport.sharedDirectoryFileUrl(token, item);
        boolean previewEnabled = sharedPreviewPolicy.isEnabled(shareLink, fileActionRegistry.resolve(item));
        return new SharedBrowserPayloads.Entry(
                item.name(),
                item.path(),
                item.directory(),
                item.hidden(),
                item.sizeLabel(),
                item.modifiedLabel(),
                item.mediaType(),
                item.typeLabel(),
                item.directory() ? SharedFileRoutes.directoryUrl(token, item.path()) : detailUrl,
                detailUrl,
                item.directory() ? null : filePreviewSupport.sharedDirectoryDownloadUrl(token, item),
                previewEnabled ? detailUrl : null
        );
    }

    private void requireDirectory(ShareLink shareLink) throws NoSuchFileException {
        if (shareLink.type() != ShareTargetType.DIRECTORY) {
            throw new NoSuchFileException("Shared directory is unavailable.");
        }
    }

    private void recordAccess(ShareLink shareLink, HttpServletRequest request, String targetPath, String message) {
        activityLogService.record("SHARE_ACCESS", request, shareLink.path(), targetPath, message,
                Map.of("tokenFingerprint", publicLinkTokenService.fingerprint(shareLink.token())));
    }

}
