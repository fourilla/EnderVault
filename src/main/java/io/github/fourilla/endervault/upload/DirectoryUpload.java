package io.github.fourilla.endervault.upload;

import java.time.Instant;
import java.util.List;
import io.github.fourilla.endervault.filecommit.FileCommitFingerprint;

public record DirectoryUpload(
        String id, String destinationPath, String name, List<Entry> files, List<String> directories,
        Instant createdAt, Instant expiresAt, Status status, String committedPath, String pendingDecisionId
) {
    public enum Status { RECEIVING, COMMITTING, PENDING, COMPLETED, CANCELING, CANCELED }

    public record Entry(String path, long size, long lastModified, String sessionId, boolean received,
            FileCommitFingerprint receipt) {
        public Entry(String path, long size, long lastModified, String sessionId, boolean received) {
            this(path, size, lastModified, sessionId, received, null);
        }
        Entry admitted(String id) { return new Entry(path, size, lastModified, id, false, null); }
        Entry complete(FileCommitFingerprint fingerprint) { return new Entry(path, size, lastModified, sessionId, true, fingerprint); }
    }

    DirectoryUpload withEntry(Entry entry) {
        return new DirectoryUpload(id, destinationPath, name,
                files.stream().map(existing -> existing.path().equals(entry.path()) ? entry : existing).toList(),
                directories, createdAt, expiresAt, status, committedPath, pendingDecisionId);
    }

    DirectoryUpload withStatus(Status next, String path, String pendingId) {
        return new DirectoryUpload(id, destinationPath, name, files, directories, createdAt, expiresAt,
                next, path, pendingId);
    }

    boolean terminal() { return status == Status.PENDING || status == Status.COMPLETED || status == Status.CANCELED; }
    boolean expired() { return !expiresAt.isAfter(Instant.now()); }
    long totalBytes() { return files.stream().mapToLong(Entry::size).sum(); }
}
