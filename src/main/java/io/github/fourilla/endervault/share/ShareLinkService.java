package io.github.fourilla.endervault.share;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.common.JsonRegistry;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService.TokenPolicy;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageService;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ShareLinkService {

    private static final TypeReference<List<ShareLink>> SHARE_LINK_LIST = new TypeReference<>() {
    };

    private final StorageService storageService;
    private final NasProperties.Share shareProperties;
    private final JsonRegistry<List<ShareLink>> registry;
    private final PublicLinkTokenService publicLinkTokenService;

    public ShareLinkService(
            StorageService storageService,
            ObjectMapper objectMapper,
            NasProperties nasProperties,
            PublicLinkTokenService publicLinkTokenService
    ) {
        this.storageService = storageService;
        this.shareProperties = nasProperties.getShare();
        this.publicLinkTokenService = publicLinkTokenService;
        this.registry = new JsonRegistry<>(
                objectMapper,
                nasProperties.getStorage().getRoot()
                        .toAbsolutePath()
                        .normalize()
                        .resolve(nasProperties.getStorage().getMetadataDirectory())
                        .resolve("shared-links.json"),
                SHARE_LINK_LIST,
                List::of,
                JsonRegistry.CorruptionPolicy.BACKUP_AND_RESET
        );
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        registry.initialize();
    }

    public synchronized ShareLink create(String directoryPath, String itemName, Instant expiresAt) throws IOException {
        return create(directoryPath, itemName, expiresAt, null);
    }

    public synchronized ShareLink create(
            String directoryPath,
            String itemName,
            Instant expiresAt,
            String customToken
    ) throws IOException {
        FileItem item = storageService.describeVaultChild(directoryPath, itemName);
        return createFromItem(item, expiresAt, customToken);
    }

    public synchronized ShareLink createForVaultPath(String vaultPath, Instant expiresAt) throws IOException {
        return createForVaultPath(vaultPath, expiresAt, null);
    }

    public synchronized ShareLink createForVaultPath(String vaultPath, Instant expiresAt, String customToken)
            throws IOException {
        if (vaultPath == null || vaultPath.isBlank() || "/".equals(vaultPath)) {
            throw new StorageAccessException("Path is required.");
        }
        FileItem item = storageService.describeVaultPath(vaultPath);
        return createFromItem(item, expiresAt, customToken);
    }

    public synchronized List<ShareLink> listForVaultPath(String vaultPath) throws IOException {
        FileItem item = storageService.describeVaultPath(vaultPath);
        return readAllMutable().stream()
                .filter(link -> link.path().equals(item.path()))
                .sorted(Comparator.comparing(ShareLink::createdAt).reversed())
                .toList();
    }

    private ShareLink createFromItem(FileItem item, Instant expiresAt, String customToken) throws IOException {
        ensureSharingEnabled();
        ShareTargetType type = item.directory() ? ShareTargetType.DIRECTORY : ShareTargetType.FILE;
        if (type == ShareTargetType.DIRECTORY && !shareProperties.isDirectoryShareEnabled()) {
            throw new StorageAccessException("Directory sharing is disabled.");
        }
        List<ShareLink> links = readAllMutable();
        String token = publicLinkTokenService.issue(
                customToken,
                links.stream().map(ShareLink::token).toList(),
                tokenPolicy()
        );
        ShareLink shareLink = new ShareLink(token, item.path(), type, Instant.now(), expiresAt, true);

        links.add(shareLink);
        writeAll(links);
        return shareLink;
    }

    public synchronized List<ShareLink> list() throws IOException {
        return readAllMutable().stream()
                .sorted(Comparator.comparing(ShareLink::createdAt).reversed())
                .toList();
    }

    public synchronized ShareLink requireUsable(String token) throws IOException {
        ensureSharingEnabled();
        ShareLink shareLink = find(token)
                .filter(link -> link.usable(Instant.now()))
                .orElseThrow(() -> new NoSuchFileException("Share link is unavailable."));

        storageService.describeVaultPath(shareLink.path());
        return shareLink;
    }

    public synchronized void revoke(String token) throws IOException {
        List<ShareLink> links = readAllMutable();
        boolean changed = false;
        for (int i = 0; i < links.size(); i++) {
            ShareLink link = links.get(i);
            if (link.token().equals(token) && link.enabled()) {
                links.set(i, link.revoke());
                changed = true;
            }
        }
        if (changed) {
            writeAll(links);
        }
    }

    public synchronized void delete(String token) throws IOException {
        List<ShareLink> links = readAllMutable();
        boolean changed = links.removeIf(link -> link.token().equals(token));
        if (changed) {
            writeAll(links);
        }
    }

    public synchronized int deleteExpired(Instant now) throws IOException {
        List<ShareLink> links = readAllMutable();
        int originalSize = links.size();
        links.removeIf(link -> link.expired(now));
        int deletedCount = originalSize - links.size();
        if (deletedCount > 0) {
            writeAll(links);
        }
        return deletedCount;
    }

    public synchronized void moveVaultPath(String oldPath, String newPath) throws IOException {
        List<ShareLink> links = readAllMutable();
        boolean changed = false;
        for (int i = 0; i < links.size(); i++) {
            ShareLink link = links.get(i);
            if (matchesPathOrDescendant(link.path(), oldPath)) {
                links.set(i, link.withPath(rebasedPath(link.path(), oldPath, newPath)));
                changed = true;
            }
        }
        if (changed) {
            writeAll(links);
        }
    }

    public synchronized void revokeVaultPath(String vaultPath) throws IOException {
        List<ShareLink> links = readAllMutable();
        boolean changed = false;
        for (int i = 0; i < links.size(); i++) {
            ShareLink link = links.get(i);
            if (link.enabled() && matchesPathOrDescendant(link.path(), vaultPath)) {
                links.set(i, link.revoke());
                changed = true;
            }
        }
        if (changed) {
            writeAll(links);
        }
    }

    private Optional<ShareLink> find(String token) throws IOException {
        return readAllMutable().stream()
                .filter(link -> link.token().equals(token))
                .findFirst();
    }

    private List<ShareLink> readAllMutable() throws IOException {
        return new ArrayList<>(registry.read());
    }

    private void writeAll(List<ShareLink> links) throws IOException {
        registry.write(List.copyOf(links));
    }

    private boolean matchesPathOrDescendant(String candidatePath, String basePath) {
        return candidatePath.equals(basePath) || candidatePath.startsWith(basePath + "/");
    }

    private String rebasedPath(String candidatePath, String oldPath, String newPath) {
        if (candidatePath.equals(oldPath)) {
            return newPath;
        }
        return newPath + candidatePath.substring(oldPath.length());
    }

    private void ensureSharingEnabled() {
        if (!shareProperties.isEnabled()) {
            throw new StorageAccessException("Sharing is disabled.");
        }
    }

    private TokenPolicy tokenPolicy() {
        return new TokenPolicy(
                "share",
                shareProperties.isCustomTokenEnabled(),
                shareProperties.getCustomTokenMinLength(),
                shareProperties.getCustomTokenMaxLength(),
                shareProperties.getRandomTokenBytes()
        );
    }
}
