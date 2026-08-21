package io.github.fourilla.endervault.metadata;

import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.upload.ResumableUploadCoordinator;
import io.github.fourilla.endervault.upload.ResumableUploadProtocolService;
import io.github.fourilla.endervault.upload.ResumableUploadService;
import io.github.fourilla.endervault.upload.ResumableUploadSession;
import io.github.fourilla.endervault.upload.ResumableUploadStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ResumableUploadMetadataInspector implements MetadataInspector {

    private final ResumableUploadService uploadService;
    private final ResumableUploadProtocolService protocolService;
    private final ResumableUploadCoordinator coordinator;
    private final StorageService storageService;

    public ResumableUploadMetadataInspector(
            ResumableUploadService uploadService,
            ResumableUploadProtocolService protocolService,
            ResumableUploadCoordinator coordinator,
            StorageService storageService
    ) {
        this.uploadService = uploadService;
        this.protocolService = protocolService;
        this.coordinator = coordinator;
        this.storageService = storageService;
    }

    @Override
    public MetadataArea area() {
        return MetadataArea.RESUMABLE_UPLOADS;
    }

    @Override
    public List<MetadataIssue> inspect() throws IOException {
        Instant now = Instant.now();
        List<MetadataIssue> issues = new ArrayList<>();
        for (ResumableUploadSession session : uploadService.list()) {
            MetadataIssue issue = issue(session, now);
            if (issue.repairable() || !session.status().terminal()) {
                issues.add(issue);
            }
        }
        return List.copyOf(issues);
    }

    @Override
    public String repair(MetadataIssueAction action, String subject) throws IOException {
        if (action != MetadataIssueAction.DELETE_RESUMABLE_UPLOAD) {
            throw new IllegalArgumentException("Unsupported resumable upload repair action.");
        }
        ResumableUploadSession session = uploadService.require(subject);
        MetadataIssue currentIssue = issue(session, Instant.now());
        if (!currentIssue.repairable()) {
            throw new IllegalArgumentException("This resumable upload is still active.");
        }
        coordinator.deleteProtocolDataIfPresent(session);
        uploadService.remove(session.id());
        return "Deleted resumable upload session: " + session.originalFilename();
    }

    private MetadataIssue issue(ResumableUploadSession session, Instant now) throws IOException {
        String problem = problem(session, now);
        boolean repairable = problem != null;
        String source = session.source().name().toLowerCase().replace('_', ' ');
        String detail = session.originalFilename()
                + " (" + session.size() + " bytes, " + source + ", status "
                + session.status().name().toLowerCase() + ")";
        return new MetadataIssue(
                area(),
                repairable ? MetadataIssueSeverity.WARNING : MetadataIssueSeverity.INFO,
                repairable ? MetadataIssueAction.DELETE_RESUMABLE_UPLOAD : MetadataIssueAction.NONE,
                session.id(),
                repairable ? problem : "Active resumable upload session",
                detail,
                repairable
                        ? "Delete this session and any disposable upload data."
                        : "Review only. Active sessions are retained so the browser can resume them."
        );
    }

    private String problem(ResumableUploadSession session, Instant now) throws IOException {
        if (session.expired(now)) {
            return "Expired resumable upload session remains";
        }
        if (session.status() == ResumableUploadStatus.FAILED) {
            return "Failed resumable upload session remains";
        }
        if (session.status().terminal()
                && session.protocolUploadUri() != null
                && protocolService.uploadDataExists(session.protocolUploadUri(), session.id())) {
            return "Terminal resumable upload protocol data remains";
        }
        if (session.status() == ResumableUploadStatus.UPLOADING
                && !protocolService.uploadDataExists(session.protocolUploadUri(), session.id())) {
            return "Resumable protocol data is missing";
        }
        if (session.status() == ResumableUploadStatus.STAGED && !stagingDataExists(session)) {
            return "Completed upload staging data is missing";
        }
        if (session.status() == ResumableUploadStatus.FINALIZING
                && !stagingDataExists(session)
                && !committedDataExists(session)) {
            return "Finalizing upload has no recoverable data";
        }
        return null;
    }

    private boolean stagingDataExists(ResumableUploadSession session) throws IOException {
        Path path = session.stagingFilename() == null
                ? storageService.resumableUploadStagingFile(session.id())
                : storageService.resolveFileStagingFile(session.stagingFilename());
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
    }

    private boolean committedDataExists(ResumableUploadSession session) {
        if (session.committedPath() == null) {
            return false;
        }
        try {
            var item = storageService.describeVaultPath(session.committedPath());
            return !item.directory() && item.size() == session.size();
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }
}
