package io.github.fourilla.endervault.upload;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PendingUploadConflictService {

    private static final Logger logger = LoggerFactory.getLogger(PendingUploadConflictService.class);

    private final Map<String, PendingUploadConflict> conflicts = new ConcurrentHashMap<>();
    private final NasProperties.Upload uploadProperties;

    public PendingUploadConflictService(NasProperties nasProperties) {
        this.uploadProperties = nasProperties.getUpload();
    }

    public PendingUploadConflict create(Path temporaryFile, String directoryPath, String filename, long size)
            throws IOException {
        cleanupExpired();
        PendingUploadConflict conflict = new PendingUploadConflict(
                UUID.randomUUID().toString(),
                temporaryFile,
                directoryPath,
                filename,
                size,
                Instant.now()
        );
        conflicts.put(conflict.id(), conflict);
        return conflict;
    }

    public PendingUploadConflict resolve(String id) throws IOException {
        cleanupExpired();
        PendingUploadConflict conflict = conflicts.remove(cleanId(id));
        if (conflict == null) {
            throw new StorageAccessException("Pending upload was not found or expired.");
        }
        return conflict;
    }

    public void cancel(String id) throws IOException {
        PendingUploadConflict conflict = conflicts.remove(cleanId(id));
        if (conflict != null) {
            Files.deleteIfExists(conflict.temporaryFile());
        }
    }

    @Scheduled(fixedDelayString = "${nas.upload.temp-cleanup-interval-ms:600000}")
    public void cleanupExpiredConflicts() {
        try {
            cleanupExpired();
        } catch (IOException ex) {
            logger.warn("Failed to clean up pending upload conflicts.", ex);
        }
    }

    @PreDestroy
    public void cleanup() {
        for (PendingUploadConflict conflict : new ArrayList<>(conflicts.values())) {
            try {
                Files.deleteIfExists(conflict.temporaryFile());
            } catch (IOException ignored) {
                // Best effort cleanup on shutdown.
            }
        }
        conflicts.clear();
    }

    private void cleanupExpired() throws IOException {
        Instant now = Instant.now();
        Duration expiration = Duration.ofMinutes(Math.max(1, uploadProperties.getTempRetentionMinutes()));
        for (PendingUploadConflict conflict : new ArrayList<>(conflicts.values())) {
            if (conflict.expired(now, expiration) && conflicts.remove(conflict.id(), conflict)) {
                Files.deleteIfExists(conflict.temporaryFile());
            }
        }
    }

    private String cleanId(String id) {
        if (id == null || id.isBlank()) {
            throw new StorageAccessException("Pending upload id is required.");
        }
        return id.trim();
    }

    public record PendingUploadConflict(
            String id,
            Path temporaryFile,
            String directoryPath,
            String filename,
            long size,
            Instant createdAt
    ) {
        boolean expired(Instant now, Duration expiration) {
            return createdAt.plus(expiration).isBefore(now);
        }
    }
}
