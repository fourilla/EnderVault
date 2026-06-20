package io.github.fourilla.endervault.metadata;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.share.ShareLink;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.task.TaskContext;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ShareLinkMetadataInspector implements MetadataInspector {

    private final ShareLinkService shareLinkService;
    private final StorageService storageService;

    public ShareLinkMetadataInspector(ShareLinkService shareLinkService, StorageService storageService) {
        this.shareLinkService = shareLinkService;
        this.storageService = storageService;
    }

    @Override
    public MetadataArea area() {
        return MetadataArea.SHARE_LINKS;
    }

    @Override
    public List<MetadataIssue> inspect() throws IOException {
        return inspect(null);
    }

    @Override
    public List<MetadataIssue> inspect(TaskContext context) throws IOException {
        Instant now = Instant.now();
        List<MetadataIssue> issues = new ArrayList<>();
        for (ShareLink link : shareLinkService.list()) {
            if (context != null) {
                context.checkCanceled();
            }
            if (link.expired(now)) {
                issues.add(new MetadataIssue(
                        area(),
                        MetadataIssueSeverity.INFO,
                        MetadataIssueAction.DELETE_METADATA,
                        link.token(),
                        "Shared link is expired",
                        link.token() + " -> " + link.path(),
                        "Delete the expired shared link from the list."
                ));
                continue;
            }

            try {
                storageService.describeVaultPath(link.path());
            } catch (IOException | StorageAccessException ex) {
                MetadataIssueAction action = link.enabled()
                        ? MetadataIssueAction.REVOKE_SHARE
                        : MetadataIssueAction.DELETE_METADATA;
                issues.add(new MetadataIssue(
                        area(),
                        MetadataIssueSeverity.WARNING,
                        action,
                        link.token(),
                        "Shared link target is missing",
                        link.token() + " -> " + link.path(),
                        link.enabled()
                                ? "Revoke this active but broken shared link."
                                : "Delete this revoked shared link metadata."
                ));
            }
        }
        return List.copyOf(issues);
    }

    @Override
    public String repair(MetadataIssueAction action, String subject) throws IOException {
        if (action == MetadataIssueAction.DELETE_METADATA) {
            shareLinkService.delete(subject);
            return "Deleted shared link metadata: " + subject;
        }
        if (action == MetadataIssueAction.REVOKE_SHARE) {
            shareLinkService.revoke(subject);
            return "Revoked broken shared link: " + subject;
        }
        throw new IllegalArgumentException("Unsupported shared link repair action.");
    }
}
