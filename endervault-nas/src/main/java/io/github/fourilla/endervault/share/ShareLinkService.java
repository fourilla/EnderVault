package io.github.fourilla.endervault.share;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageService;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class ShareLinkService {

    private static final TypeReference<List<ShareLink>> SHARE_LINK_LIST = new TypeReference<>() {
    };
    private static final Pattern CUSTOM_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{3,64}");

    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final Path registryFile;
    private final SecureRandom secureRandom = new SecureRandom();

    public ShareLinkService(StorageService storageService, ObjectMapper objectMapper, NasProperties nasProperties) {
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.registryFile = nasProperties.getStorage().getRoot()
                .toAbsolutePath()
                .normalize()
                .resolve(nasProperties.getStorage().getMetadataDirectory())
                .resolve("shared-links.json");
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        Files.createDirectories(registryFile.getParent());
        if (!Files.exists(registryFile)) {
            writeAll(List.of());
        }
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
        ShareTargetType type = item.directory() ? ShareTargetType.DIRECTORY : ShareTargetType.FILE;
        List<ShareLink> links = readAllMutable();
        String token = requestedToken(customToken, links);
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
        ShareLink shareLink = find(token)
                .filter(link -> link.usable(Instant.now()))
                .orElseThrow(() -> new NoSuchFileException(token));

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
        if (!Files.exists(registryFile) || Files.size(registryFile) == 0L) {
            return new ArrayList<>();
        }
        return new ArrayList<>(objectMapper.readValue(registryFile.toFile(), SHARE_LINK_LIST));
    }

    private void writeAll(List<ShareLink> links) throws IOException {
        Files.createDirectories(registryFile.getParent());
        Path tempFile = registryFile.resolveSibling(registryFile.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), links);
        try {
            Files.move(tempFile, registryFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tempFile, registryFile, StandardCopyOption.REPLACE_EXISTING);
        }
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

    private String requestedToken(String customToken, List<ShareLink> existingLinks) {
        if (customToken == null || customToken.isBlank()) {
            return newToken(existingLinks);
        }

        String token = customToken.trim();
        if (!CUSTOM_TOKEN_PATTERN.matcher(token).matches()) {
            throw new StorageAccessException("Share token must be 3-64 characters using letters, numbers, '-' or '_'.");
        }
        if (tokenExists(existingLinks, token)) {
            throw new StorageAccessException("Share token already exists.");
        }
        return token;
    }

    private String newToken(List<ShareLink> existingLinks) {
        String token;
        do {
            byte[] bytes = new byte[24];
            secureRandom.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (tokenExists(existingLinks, token));
        return token;
    }

    private boolean tokenExists(List<ShareLink> existingLinks, String token) {
        return existingLinks.stream().anyMatch(link -> link.token().equals(token));
    }
}
