package io.github.fourilla.endervault.metadata;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.task.TaskContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PendingFileDecisionMetadataInspector implements MetadataInspector {

    private final PendingFileDecisionService pendingFileDecisionService;
    private final StorageService storageService;
    private final Duration warningAge;

    public PendingFileDecisionMetadataInspector(
            PendingFileDecisionService pendingFileDecisionService,
            StorageService storageService,
            NasProperties nasProperties
    ) {
        this.pendingFileDecisionService = pendingFileDecisionService;
        this.storageService = storageService;
        this.warningAge = Duration.ofDays(nasProperties.getPendingFileDecisions().getWarningDays());
    }

    @Override
    public MetadataArea area() {
        return MetadataArea.PENDING_FILE_DECISIONS;
    }

    @Override
    public List<MetadataIssue> inspect() throws IOException {
        return inspect(null);
    }

    @Override
    public List<MetadataIssue> inspect(TaskContext context) throws IOException {
        Instant now = Instant.now();
        List<MetadataIssue> issues = new ArrayList<>();
        for (PendingFileDecision decision : pendingFileDecisionService.list()) {
            if (context != null) {
                context.checkCanceled();
            }
            Path stagedFile = storageService.resolveFileStagingFile(decision.stagingFilename());
            String detail = decision.originalFilename() + " / " + destinationLabel(decision.destinationPath())
                    + " / received " + decision.createdAt();
            if (!safeRegularFile(stagedFile)) {
                issues.add(new MetadataIssue(
                        area(),
                        MetadataIssueSeverity.DANGER,
                        MetadataIssueAction.REMOVE_MISSING_PENDING_DECISION,
                        decision.id(),
                        "Pending decision data is missing or unsafe",
                        detail,
                        "Remove the broken decision record and release its reserved request quota."
                ));
                continue;
            }

            boolean destinationMissing = false;
            try {
                storageService.resolveVaultDirectory(decision.destinationPath());
            } catch (IOException | StorageAccessException ex) {
                destinationMissing = true;
            }
            boolean old = decision.createdAt().plus(warningAge).isBefore(now);
            if (destinationMissing || old) {
                issues.add(new MetadataIssue(
                        area(),
                        destinationMissing ? MetadataIssueSeverity.DANGER : MetadataIssueSeverity.WARNING,
                        MetadataIssueAction.NONE,
                        decision.id(),
                        destinationMissing
                                ? "Pending decision destination is missing"
                                : "Pending decision has been waiting a long time",
                        detail,
                        destinationMissing
                                ? "Recreate the destination or discard the file from Pending File Decisions."
                                : "Review this file from Pending File Decisions; it is not deleted automatically."
                ));
            }
        }
        return List.copyOf(issues);
    }

    @Override
    public String repair(MetadataIssueAction action, String subject) throws IOException {
        if (action != MetadataIssueAction.REMOVE_MISSING_PENDING_DECISION) {
            throw new IllegalArgumentException("Unsupported pending decision repair action.");
        }
        pendingFileDecisionService.removeMissingData(subject);
        return "Removed pending decision with missing data: " + subject;
    }

    private boolean safeRegularFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
    }

    private String destinationLabel(String path) {
        return path == null || path.isBlank() ? "/" : "/" + path;
    }
}
