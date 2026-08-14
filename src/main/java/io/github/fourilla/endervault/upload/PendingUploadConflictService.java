package io.github.fourilla.endervault.upload;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import io.github.fourilla.endervault.temporary.TemporaryArtifactType;
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
    private final Map<String, TemporaryArtifactRegistry.Registration> registrations = new ConcurrentHashMap<>();
    private final NasProperties.Upload uploadProperties;
    private final TemporaryArtifactRegistry temporaryArtifactRegistry;

    public PendingUploadConflictService(
            NasProperties nasProperties,
            TemporaryArtifactRegistry temporaryArtifactRegistry
    ) {
        this.uploadProperties = nasProperties.getUpload();
        this.temporaryArtifactRegistry = temporaryArtifactRegistry;
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
        TemporaryArtifactRegistry.Registration registration = temporaryArtifactRegistry.register(
                temporaryFile,
                TemporaryArtifactType.UPLOAD_CONFLICT,
                conflict.id()
        );
        registrations.put(conflict.id(), registration);
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
        String normalizedId = cleanId(id);
        PendingUploadConflict conflict = conflicts.remove(normalizedId);
        try {
            if (conflict != null) {
                Files.deleteIfExists(conflict.temporaryFile());
            }
        } finally {
            release(normalizedId);
        }
    }

    public void release(PendingUploadConflict conflict) {
        if (conflict != null) {
            release(conflict.id());
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
            } finally {
                release(conflict.id());
            }
        }
        conflicts.clear();
        registrations.values().forEach(TemporaryArtifactRegistry.Registration::close);
        registrations.clear();
    }

    private void cleanupExpired() throws IOException {
        Instant now = Instant.now();
        Duration expiration = Duration.ofMinutes(Math.max(1, uploadProperties.getTempRetentionMinutes()));
        for (PendingUploadConflict conflict : new ArrayList<>(conflicts.values())) {
            if (conflict.expired(now, expiration) && conflicts.remove(conflict.id(), conflict)) {
                try {
                    Files.deleteIfExists(conflict.temporaryFile());
                } finally {
                    release(conflict.id());
                }
            }
        }
    }

    private void release(String id) {
        TemporaryArtifactRegistry.Registration registration = registrations.remove(id);
        if (registration != null) {
            registration.close();
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
