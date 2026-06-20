package io.github.fourilla.endervault.metadata;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.recent.RecentItem;
import io.github.fourilla.endervault.recent.RecentService;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.task.TaskContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RecentMetadataInspector implements MetadataInspector {

    private final RecentService recentService;
    private final StorageService storageService;

    public RecentMetadataInspector(RecentService recentService, StorageService storageService) {
        this.recentService = recentService;
        this.storageService = storageService;
    }

    @Override
    public MetadataArea area() {
        return MetadataArea.RECENT;
    }

    @Override
    public List<MetadataIssue> inspect() throws IOException {
        return inspect(null);
    }

    @Override
    public List<MetadataIssue> inspect(TaskContext context) throws IOException {
        List<MetadataIssue> issues = new ArrayList<>();
        for (RecentItem recent : recentService.storedItems()) {
            if (context != null) {
                context.checkCanceled();
            }
            try {
                storageService.describeVaultPath(recent.path());
            } catch (IOException | StorageAccessException ex) {
                issues.add(new MetadataIssue(
                        area(),
                        MetadataIssueSeverity.WARNING,
                        MetadataIssueAction.REMOVE_METADATA,
                        recent.path(),
                        "Recent item target is missing",
                        recent.path(),
                        "Remove this stale recent item entry."
                ));
            }
        }
        return List.copyOf(issues);
    }

    @Override
    public String repair(MetadataIssueAction action, String subject) throws IOException {
        if (action != MetadataIssueAction.REMOVE_METADATA) {
            throw new IllegalArgumentException("Unsupported recent repair action.");
        }
        recentService.remove(subject);
        return "Removed stale recent metadata: " + subject;
    }
}
