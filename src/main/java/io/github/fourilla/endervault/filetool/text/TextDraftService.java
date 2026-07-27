package io.github.fourilla.endervault.filetool.text;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.storage.FileDetail;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TextDraftService {

    private final TextDraftRepository repository;
    private final TextFileService textFileService;
    private final NasProperties.FileTools fileTools;

    public TextDraftService(
            TextDraftRepository repository,
            TextFileService textFileService,
            NasProperties nasProperties
    ) {
        this.repository = repository;
        this.textFileService = textFileService;
        this.fileTools = nasProperties.getFileTools();
    }

    public synchronized TextDraftStatus status(String vaultPath, Path source, String editorToken) throws IOException {
        Optional<TextDraftRecord> draft = repository.findByVaultPath(vaultPath);
        if (draft.isEmpty()) {
            return TextDraftStatus.missing();
        }
        return status(draft.get(), source, editorToken, Instant.now());
    }

    public synchronized TextDraftStatus autosave(
            FileDetail detail,
            Path source,
            String content,
            String editorToken,
            boolean takeOver
    ) throws IOException {
        TextDraftRecord record = saveDraft(detail, source, content, editorToken, takeOver);
        return status(record, source, editorToken, Instant.now());
    }

    public synchronized TextDraftSnapshot restore(
            FileDetail detail,
            Path source,
            String editorToken,
            boolean takeOver
    ) throws IOException {
        textFileService.requireTextTool(detail);
        String token = requireEditorToken(editorToken);
        TextDraftRecord existing = repository.findByVaultPath(detail.path())
                .orElseThrow(() -> new StorageAccessException("Text draft was not found."));
        Instant now = Instant.now();
        requireLease(existing, source, token, takeOver, now);

        TextDraftRecord claimed = existing.withLease(token, now, leaseExpiresAt(now));
        String content = repository.readContent(existing.id());
        repository.save(claimed, textFileService.validatedBytes(content));
        return new TextDraftSnapshot(status(claimed, source, token, now), content);
    }

    public synchronized void discard(String vaultPath, Path source, String editorToken, boolean takeOver)
            throws IOException {
        TextDraftRecord existing = repository.findByVaultPath(vaultPath).orElse(null);
        if (existing == null) {
            return;
        }
        String token = requireEditorToken(editorToken);
        requireLease(existing, source, token, takeOver, Instant.now());
        repository.delete(existing.id());
    }

    public synchronized void saveToSource(
            FileDetail detail,
            Path source,
            String content,
            String editorToken,
            boolean forceOverwrite
    ) throws IOException {
        TextDraftRecord record = saveDraft(detail, source, content, editorToken, false);
        if (!forceOverwrite && !record.originalFingerprint().matches(source)) {
            throw new TextDraftSourceConflictException(
                    "The original file changed after this draft was created. The draft was kept."
            );
        }

        textFileService.writeText(detail, source, content);
        repository.delete(record.id());
    }

    public synchronized void saveDirect(FileDetail detail, Path source, String content) throws IOException {
        textFileService.writeText(detail, source, content);
        TextDraftRecord draft = repository.findByVaultPath(detail.path()).orElse(null);
        if (draft != null) {
            repository.delete(draft.id());
        }
    }

    public synchronized void moveVaultPath(String oldPath, String newPath) throws IOException {
        repository.moveVaultPath(oldPath, newPath);
    }

    public synchronized int cleanupExpired() throws IOException {
        Instant cutoff = Instant.now().minus(Duration.ofHours(retentionHours()));
        int deleted = 0;
        for (TextDraftRecord record : repository.records()) {
            if (record.updatedAt().isBefore(cutoff) && !isActive(record, Instant.now())) {
                repository.delete(record.id());
                deleted++;
            }
        }
        return deleted;
    }

    public List<TextDraftRecord> records() throws IOException {
        return repository.records();
    }

    public List<TextDraftContentFile> contentFiles() throws IOException {
        return repository.contentFiles();
    }

    public boolean contentExists(UUID id) {
        return repository.contentExists(id);
    }

    public void delete(UUID id) throws IOException {
        repository.delete(id);
    }

    public void deleteMetadata(UUID id) throws IOException {
        repository.deleteMetadata(id);
    }

    public void deleteContentFile(String fileName) throws IOException {
        repository.deleteContentFile(fileName);
    }

    public String contentFileName(UUID id) {
        return repository.contentFileName(id);
    }

    public long retentionHours() {
        return Math.max(1L, fileTools.getTextDraftRetentionHours());
    }

    private TextDraftRecord saveDraft(
            FileDetail detail,
            Path source,
            String content,
            String editorToken,
            boolean takeOver
    ) throws IOException {
        textFileService.requireTextTool(detail);
        byte[] contentBytes = textFileService.validatedBytes(content);
        String token = requireEditorToken(editorToken);
        Instant now = Instant.now();
        TextDraftRecord existing = repository.findByVaultPath(detail.path()).orElse(null);
        TextDraftRecord record;
        if (existing == null) {
            record = new TextDraftRecord(
                    UUID.randomUUID(),
                    detail.path(),
                    TextSourceFingerprint.capture(source),
                    now,
                    now,
                    token,
                    leaseExpiresAt(now)
            );
        } else {
            requireLease(existing, source, token, takeOver, now);
            record = existing.withLease(token, now, leaseExpiresAt(now));
        }
        repository.save(record, contentBytes);
        return record;
    }

    private void requireLease(
            TextDraftRecord record,
            Path source,
            String editorToken,
            boolean takeOver,
            Instant now
    ) throws IOException {
        if (isActive(record, now) && !editorToken.equals(record.editorToken()) && !takeOver) {
            throw new TextDraftLeaseException(
                    "This text draft is being edited in another tab or device.",
                    status(record, source, editorToken, now)
            );
        }
    }

    private TextDraftStatus status(
            TextDraftRecord record,
            Path source,
            String editorToken,
            Instant now
    ) throws IOException {
        boolean active = isActive(record, now);
        boolean owned = active && cleanToken(editorToken).equals(record.editorToken());
        boolean sourceChanged = !Files.isRegularFile(source)
                || !record.originalFingerprint().matches(source);
        return new TextDraftStatus(
                true,
                active,
                owned,
                sourceChanged,
                record.updatedAt(),
                record.leaseExpiresAt()
        );
    }

    private boolean isActive(TextDraftRecord record, Instant now) {
        return record.leaseExpiresAt() != null && record.leaseExpiresAt().isAfter(now);
    }

    private Instant leaseExpiresAt(Instant now) {
        return now.plusSeconds(Math.max(30L, fileTools.getTextDraftLeaseSeconds()));
    }

    private String requireEditorToken(String value) {
        String token = cleanToken(value);
        if (!token.matches("[A-Za-z0-9_-]{16,128}")) {
            throw new StorageAccessException("Text editor token is invalid.");
        }
        return token;
    }

    private String cleanToken(String value) {
        return value == null ? "" : value.trim();
    }
}
