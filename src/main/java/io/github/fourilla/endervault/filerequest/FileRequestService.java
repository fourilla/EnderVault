package io.github.fourilla.endervault.filerequest;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService.TokenPolicy;
import io.github.fourilla.endervault.storage.StorageService;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FileRequestService {

    public static final long HARD_MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024 * 1024;
    public static final long HARD_MAX_TOTAL_BYTES = 100L * 1024 * 1024 * 1024;
    public static final int HARD_MAX_FILES = 1_000;
    public static final int HARD_MAX_EXPIRATION_DAYS = 365;
    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_EXTENSION_COUNT = 50;
    private static final int MAX_EXTENSION_LENGTH = 24;

    private final FileRequestRepository repository;
    private final StorageService storageService;
    private final PublicLinkTokenService publicLinkTokenService;
    private final NasProperties.FileRequest properties;

    public FileRequestService(
            FileRequestRepository repository,
            StorageService storageService,
            PublicLinkTokenService publicLinkTokenService,
            NasProperties nasProperties
    ) {
        this.repository = repository;
        this.storageService = storageService;
        this.publicLinkTokenService = publicLinkTokenService;
        this.properties = nasProperties.getFileRequest();
    }

    public synchronized FileRequest create(
            String title,
            String destinationPath,
            UploaderNamePolicy uploaderNamePolicy,
            long maxFileSizeBytes,
            long maxTotalBytes,
            int maxFiles,
            List<String> allowedExtensions,
            int expirationDays,
            String customToken
    ) throws IOException {
        ensureEnabled();
        String normalizedTitle = cleanTitle(title);
        String normalizedDestination;
        try {
            normalizedDestination = storageService.normalizeVaultDirectory(destinationPath);
        } catch (NoSuchFileException ex) {
            throw new StorageAccessException("File request destination must be an existing directory.", ex);
        }
        validateLimits(maxFileSizeBytes, maxTotalBytes, maxFiles, expirationDays);
        List<String> extensions = normalizeExtensions(allowedExtensions);
        List<FileRequest> requests = readMutable();
        String token = publicLinkTokenService.issue(
                customToken,
                requests.stream().map(FileRequest::token).toList(),
                tokenPolicy()
        );
        Instant now = Instant.now();
        Instant expiresAt = expirationDays == 0 ? null : now.plus(Duration.ofDays(expirationDays));
        FileRequest fileRequest = new FileRequest(
                UUID.randomUUID().toString(),
                token,
                normalizedTitle,
                normalizedDestination,
                uploaderNamePolicy == null ? UploaderNamePolicy.OPTIONAL : uploaderNamePolicy,
                maxFileSizeBytes,
                maxTotalBytes,
                maxFiles,
                extensions,
                0L,
                0,
                now,
                expiresAt,
                true
        );
        requests.add(fileRequest);
        repository.write(requests);
        return fileRequest;
    }

    public synchronized List<FileRequest> list() throws IOException {
        return repository.list().stream()
                .sorted(Comparator.comparing(FileRequest::createdAt).reversed())
                .toList();
    }

    public synchronized FileRequest require(String id) throws IOException {
        String normalizedId = cleanId(id);
        return repository.list().stream()
                .filter(request -> request.id().equals(normalizedId))
                .findFirst()
                .orElseThrow(() -> new NoSuchFileException("File request was not found."));
    }

    public synchronized FileRequest requireUsable(String token) throws IOException {
        ensureEnabled();
        FileRequest request = findByToken(token)
                .filter(item -> item.usable(Instant.now()))
                .orElseThrow(() -> new NoSuchFileException("File request is unavailable."));
        storageService.resolveVaultDirectory(request.destinationPath());
        return request;
    }

    public synchronized FileRequest requireUsableById(String id) throws IOException {
        FileRequest request = require(id);
        if (!request.usable(Instant.now())) {
            throw new NoSuchFileException("File request is unavailable.");
        }
        storageService.resolveVaultDirectory(request.destinationPath());
        return request;
    }

    public synchronized FileRequest recordAcceptedUpload(String id, long size) throws IOException {
        if (size < 0L) {
            throw new StorageAccessException("Uploaded file size is invalid.");
        }
        FileRequest request = requireUsableById(id);
        if (request.acceptedFiles() >= request.maxFiles()
                || size > request.maxFileSizeBytes()
                || request.acceptedBytes() > request.maxTotalBytes() - size) {
            throw new StorageAccessException("File request quota has been reached.");
        }
        FileRequest updated = request.withAcceptedUpload(size);
        replace(updated);
        return updated;
    }

    public synchronized void releaseAcceptedUpload(String id, long size) throws IOException {
        List<FileRequest> requests = readMutable();
        for (int i = 0; i < requests.size(); i++) {
            if (requests.get(i).id().equals(cleanId(id))) {
                requests.set(i, requests.get(i).withoutAcceptedUpload(size));
                repository.write(requests);
                return;
            }
        }
    }

    public synchronized void revoke(String id) throws IOException {
        update(id, FileRequest::revoke);
    }

    public synchronized void delete(String id) throws IOException {
        List<FileRequest> requests = readMutable();
        if (requests.removeIf(request -> request.id().equals(cleanId(id)))) {
            repository.write(requests);
        }
    }

    public synchronized int deleteExpired(Instant now) throws IOException {
        List<FileRequest> requests = readMutable();
        int before = requests.size();
        requests.removeIf(request -> request.expired(now));
        if (requests.size() != before) {
            repository.write(requests);
        }
        return before - requests.size();
    }

    public synchronized void moveVaultPath(String oldPath, String newPath) throws IOException {
        List<FileRequest> requests = readMutable();
        boolean changed = false;
        for (int i = 0; i < requests.size(); i++) {
            FileRequest request = requests.get(i);
            if (matchesPathOrDescendant(request.destinationPath(), oldPath)) {
                requests.set(i, request.withDestinationPath(rebasedPath(
                        request.destinationPath(), oldPath, newPath
                )));
                changed = true;
            }
        }
        if (changed) {
            repository.write(requests);
        }
    }

    public synchronized void revokeVaultPath(String vaultPath) throws IOException {
        List<FileRequest> requests = readMutable();
        boolean changed = false;
        for (int i = 0; i < requests.size(); i++) {
            FileRequest request = requests.get(i);
            if (request.enabled() && matchesPathOrDescendant(request.destinationPath(), vaultPath)) {
                requests.set(i, request.revoke());
                changed = true;
            }
        }
        if (changed) {
            repository.write(requests);
        }
    }

    private Optional<FileRequest> findByToken(String token) throws IOException {
        String normalized = token == null ? "" : token.trim();
        return repository.list().stream().filter(request -> request.token().equals(normalized)).findFirst();
    }

    private void update(String id, RequestUpdate update) throws IOException {
        String normalizedId = cleanId(id);
        List<FileRequest> requests = readMutable();
        for (int i = 0; i < requests.size(); i++) {
            if (requests.get(i).id().equals(normalizedId)) {
                requests.set(i, update.apply(requests.get(i)));
                repository.write(requests);
                return;
            }
        }
        throw new NoSuchFileException("File request was not found.");
    }

    private void replace(FileRequest replacement) throws IOException {
        List<FileRequest> requests = readMutable();
        for (int i = 0; i < requests.size(); i++) {
            if (requests.get(i).id().equals(replacement.id())) {
                requests.set(i, replacement);
                repository.write(requests);
                return;
            }
        }
        throw new NoSuchFileException("File request was not found.");
    }

    private void validateLimits(long maxFileSizeBytes, long maxTotalBytes, int maxFiles, int expirationDays) {
        if (maxFileSizeBytes < 1 || maxFileSizeBytes > HARD_MAX_FILE_SIZE_BYTES) {
            throw new StorageAccessException("Maximum file size must be between 1 byte and 20 GB.");
        }
        if (maxTotalBytes < maxFileSizeBytes || maxTotalBytes > HARD_MAX_TOTAL_BYTES) {
            throw new StorageAccessException("Total quota must be at least the file limit and no more than 100 GB.");
        }
        if (maxFiles < 1 || maxFiles > HARD_MAX_FILES) {
            throw new StorageAccessException("Maximum file count must be between 1 and 1000.");
        }
        if (expirationDays < 0 || expirationDays > HARD_MAX_EXPIRATION_DAYS) {
            throw new StorageAccessException("Expiration must be 0-365 days.");
        }
    }

    private List<String> normalizeExtensions(List<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : values == null ? List.<String>of() : values) {
            for (String part : raw.split(",")) {
                String extension = part.trim().toLowerCase(Locale.ROOT);
                while (extension.startsWith(".")) {
                    extension = extension.substring(1);
                }
                if (extension.isBlank()) {
                    continue;
                }
                if (extension.length() > MAX_EXTENSION_LENGTH || !extension.matches("[a-z0-9][a-z0-9._+-]*")) {
                    throw new StorageAccessException("Allowed extensions contain an invalid value.");
                }
                normalized.add(extension);
                if (normalized.size() > MAX_EXTENSION_COUNT) {
                    throw new StorageAccessException("At most 50 allowed extensions may be configured.");
                }
            }
        }
        return List.copyOf(normalized);
    }

    private String cleanTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new StorageAccessException("File request title is required.");
        }
        String normalized = title.trim();
        if (normalized.length() > MAX_TITLE_LENGTH) {
            throw new StorageAccessException("File request title must be 120 characters or fewer.");
        }
        return normalized;
    }

    private String cleanId(String id) {
        if (id == null || id.isBlank()) {
            throw new StorageAccessException("File request id is required.");
        }
        return id.trim();
    }

    private List<FileRequest> readMutable() throws IOException {
        return new ArrayList<>(repository.list());
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new StorageAccessException("File requests are disabled.");
        }
    }

    private TokenPolicy tokenPolicy() {
        return new TokenPolicy(
                "file request",
                properties.isCustomTokenEnabled(),
                properties.getCustomTokenMinLength(),
                properties.getCustomTokenMaxLength(),
                properties.getRandomTokenBytes()
        );
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

    @FunctionalInterface
    private interface RequestUpdate {
        FileRequest apply(FileRequest request);
    }
}
