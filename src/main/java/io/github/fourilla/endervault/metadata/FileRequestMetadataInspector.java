package io.github.fourilla.endervault.metadata;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.filerequest.FileRequest;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.task.TaskContext;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FileRequestMetadataInspector implements MetadataInspector {

    private final FileRequestService fileRequestService;
    private final StorageService storageService;
    private final PublicLinkTokenService publicLinkTokenService;

    public FileRequestMetadataInspector(
            FileRequestService fileRequestService,
            StorageService storageService,
            PublicLinkTokenService publicLinkTokenService
    ) {
        this.fileRequestService = fileRequestService;
        this.storageService = storageService;
        this.publicLinkTokenService = publicLinkTokenService;
    }

    @Override
    public MetadataArea area() {
        return MetadataArea.FILE_REQUESTS;
    }

    @Override
    public List<MetadataIssue> inspect() throws IOException {
        return inspect(null);
    }

    @Override
    public List<MetadataIssue> inspect(TaskContext context) throws IOException {
        Instant now = Instant.now();
        List<MetadataIssue> issues = new ArrayList<>();
        for (FileRequest request : fileRequestService.list()) {
            if (context != null) {
                context.checkCanceled();
            }
            String detail = request.title() + " / " + destinationLabel(request.destinationPath())
                    + " / token " + publicLinkTokenService.fingerprint(request.token());
            if (request.expired(now)) {
                issues.add(new MetadataIssue(
                        area(),
                        MetadataIssueSeverity.INFO,
                        MetadataIssueAction.DELETE_FILE_REQUEST,
                        request.id(),
                        "File request is expired",
                        detail,
                        "Delete this expired file request metadata."
                ));
                continue;
            }

            try {
                storageService.resolveVaultDirectory(request.destinationPath());
            } catch (IOException | StorageAccessException ex) {
                boolean active = request.enabled();
                issues.add(new MetadataIssue(
                        area(),
                        MetadataIssueSeverity.WARNING,
                        active
                                ? MetadataIssueAction.REVOKE_FILE_REQUEST
                                : MetadataIssueAction.DELETE_FILE_REQUEST,
                        request.id(),
                        "File request destination is missing",
                        detail,
                        active
                                ? "Revoke this active request so it cannot accept new uploads."
                                : "Delete this revoked request metadata."
                ));
            }
        }
        return List.copyOf(issues);
    }

    @Override
    public String repair(MetadataIssueAction action, String subject) throws IOException {
        if (action == MetadataIssueAction.REVOKE_FILE_REQUEST) {
            fileRequestService.revoke(subject);
            return "Revoked broken file request: " + subject;
        }
        if (action == MetadataIssueAction.DELETE_FILE_REQUEST) {
            fileRequestService.delete(subject);
            return "Deleted file request metadata: " + subject;
        }
        throw new IllegalArgumentException("Unsupported file request repair action.");
    }

    private String destinationLabel(String path) {
        return path == null || path.isBlank() ? "/" : "/" + path;
    }
}
