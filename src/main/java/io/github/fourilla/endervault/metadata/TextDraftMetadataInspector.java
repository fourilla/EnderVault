package io.github.fourilla.endervault.metadata;

import io.github.fourilla.endervault.filetool.text.TextDraftContentFile;
import io.github.fourilla.endervault.filetool.text.TextDraftRecord;
import io.github.fourilla.endervault.filetool.text.TextDraftService;
import io.github.fourilla.endervault.filetool.text.TextSourceFingerprint;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.task.TaskContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TextDraftMetadataInspector implements MetadataInspector {

    private final TextDraftService textDraftService;
    private final StorageService storageService;

    public TextDraftMetadataInspector(TextDraftService textDraftService, StorageService storageService) {
        this.textDraftService = textDraftService;
        this.storageService = storageService;
    }

    @Override
    public MetadataArea area() {
        return MetadataArea.TEXT_DRAFTS;
    }

    @Override
    public List<MetadataIssue> inspect() throws IOException {
        return inspect(null);
    }

    @Override
    public List<MetadataIssue> inspect(TaskContext context) throws IOException {
        Instant now = Instant.now();
        Instant retentionCutoff = now.minus(Duration.ofHours(textDraftService.retentionHours()));
        List<TextDraftRecord> records = textDraftService.records();
        List<TextDraftContentFile> contentFiles = textDraftService.contentFiles();
        Set<String> referencedContent = new HashSet<>();
        List<MetadataIssue> issues = new ArrayList<>();

        if (context != null) {
            context.setTotalItems(records.size() + contentFiles.size());
        }

        for (TextDraftRecord record : records) {
            checkCanceled(context);
            referencedContent.add(textDraftService.contentFileName(record.id()));
            inspectRecord(record, now, retentionCutoff, issues);
            increment(context);
        }

        for (TextDraftContentFile contentFile : contentFiles) {
            checkCanceled(context);
            if (!referencedContent.contains(contentFile.fileName())) {
                issues.add(new MetadataIssue(
                        area(),
                        MetadataIssueSeverity.WARNING,
                        MetadataIssueAction.DELETE_TEXT_DRAFT_CONTENT,
                        contentFile.fileName(),
                        "Text draft content has no registry record",
                        contentFile.fileName() + " (" + contentFile.size() + " bytes)",
                        "Delete this orphan draft content file."
                ));
            }
            increment(context);
        }
        return List.copyOf(issues);
    }

    @Override
    public String repair(MetadataIssueAction action, String subject) throws IOException {
        return switch (action) {
            case DELETE_TEXT_DRAFT -> {
                UUID id = requireUuid(subject);
                textDraftService.delete(id);
                yield "Deleted text draft: " + id;
            }
            case DELETE_TEXT_DRAFT_METADATA -> {
                UUID id = requireUuid(subject);
                textDraftService.deleteMetadata(id);
                yield "Deleted text draft metadata: " + id;
            }
            case DELETE_TEXT_DRAFT_CONTENT -> {
                textDraftService.deleteContentFile(subject);
                yield "Deleted text draft content: " + subject;
            }
            default -> throw new IllegalArgumentException("Unsupported text draft repair action.");
        };
    }

    private void inspectRecord(
            TextDraftRecord record,
            Instant now,
            Instant retentionCutoff,
            List<MetadataIssue> issues
    ) throws IOException {
        if (!textDraftService.contentExists(record.id())) {
            issues.add(new MetadataIssue(
                    area(),
                    MetadataIssueSeverity.DANGER,
                    MetadataIssueAction.DELETE_TEXT_DRAFT_METADATA,
                    record.id().toString(),
                    "Text draft registry record has no content",
                    record.vaultPath(),
                    "Delete the unusable registry record."
            ));
            return;
        }

        Path source = storageService.resolveVaultPath(record.vaultPath());
        if (!Files.isRegularFile(source)) {
            issues.add(new MetadataIssue(
                    area(),
                    MetadataIssueSeverity.WARNING,
                    MetadataIssueAction.DELETE_TEXT_DRAFT,
                    record.id().toString(),
                    "Text draft source file is missing",
                    record.vaultPath(),
                    "Review the draft, then delete it if the source will not be restored."
            ));
            return;
        }

        boolean active = record.leaseExpiresAt() != null && record.leaseExpiresAt().isAfter(now);
        if (!active && record.updatedAt().isBefore(retentionCutoff)) {
            issues.add(new MetadataIssue(
                    area(),
                    MetadataIssueSeverity.INFO,
                    MetadataIssueAction.DELETE_TEXT_DRAFT,
                    record.id().toString(),
                    "Text draft exceeded its retention period",
                    record.vaultPath(),
                    "Delete this expired recoverable draft."
            ));
            return;
        }

        if (!record.originalFingerprint().matches(source)) {
            issues.add(new MetadataIssue(
                    area(),
                    MetadataIssueSeverity.WARNING,
                    MetadataIssueAction.NONE,
                    record.id().toString(),
                    "Text draft source changed",
                    record.vaultPath(),
                    "Open the file detail page and decide whether to restore, discard, or overwrite from the draft."
            ));
        }
    }

    private UUID requireUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Text draft identifier is invalid.", ex);
        }
    }

    private void checkCanceled(TaskContext context) {
        if (context != null) {
            context.checkCanceled();
        }
    }

    private void increment(TaskContext context) {
        if (context != null) {
            context.incrementProcessedItems();
        }
    }
}
