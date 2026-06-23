package io.github.fourilla.endervault.metadata;

import io.github.fourilla.endervault.storage.DirectoryListing;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.task.TaskContext;
import io.github.fourilla.endervault.trash.TrashRecord;
import io.github.fourilla.endervault.trash.TrashRepository;
import io.github.fourilla.endervault.trash.TrashService;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class TrashMetadataInspector implements MetadataInspector {

    private final TrashRepository trashRepository;
    private final TrashService trashService;
    private final StorageService storageService;

    public TrashMetadataInspector(
            TrashRepository trashRepository,
            TrashService trashService,
            StorageService storageService
    ) {
        this.trashRepository = trashRepository;
        this.trashService = trashService;
        this.storageService = storageService;
    }

    @Override
    public MetadataArea area() {
        return MetadataArea.TRASH;
    }

    @Override
    public List<MetadataIssue> inspect() throws IOException {
        return inspect(null);
    }

    @Override
    public List<MetadataIssue> inspect(TaskContext context) throws IOException {
        Instant now = Instant.now();
        List<TrashRecord> records = trashRepository.list();
        Set<String> recordedTrashNames = new HashSet<>();
        List<MetadataIssue> issues = new ArrayList<>();
        for (TrashRecord record : records) {
            if (context != null) {
                context.checkCanceled();
            }
            recordedTrashNames.add(record.trashName());
            if (!storageService.trashItemExists(record.trashName())) {
                issues.add(new MetadataIssue(
                        area(),
                        MetadataIssueSeverity.WARNING,
                        MetadataIssueAction.REMOVE_TRASH_RECORD,
                        record.id(),
                        "Trash record points to a missing file",
                        record.originalPath() + " -> " + record.trashName(),
                        "Remove this stale trash metadata record."
                ));
                continue;
            }
            if (record.expired(now)) {
                issues.add(new MetadataIssue(
                        area(),
                        MetadataIssueSeverity.INFO,
                        MetadataIssueAction.DELETE_TRASH_RECORD_ITEM,
                        record.id(),
                        "Trash item is expired",
                        record.originalPath() + " expired at " + record.expiresLabel(),
                        "Permanently delete this expired trash item."
                ));
            }
        }

        DirectoryListing listing = storageService.listTrash();
        for (FileItem item : joined(listing.directories(), listing.files())) {
            if (context != null) {
                context.checkCanceled();
            }
            if (!recordedTrashNames.contains(item.name())) {
                issues.add(new MetadataIssue(
                        area(),
                        MetadataIssueSeverity.DANGER,
                        MetadataIssueAction.DELETE_ORPHAN_TRASH_ITEM,
                        item.name(),
                        "Trash file has no metadata record",
                        item.name() + " (" + item.typeLabel() + ", " + item.sizeLabel() + ")",
                        "Delete this orphan file only if it is not needed."
                ));
            }
        }
        return List.copyOf(issues);
    }

    @Override
    public String repair(MetadataIssueAction action, String subject) throws IOException {
        if (action == MetadataIssueAction.REMOVE_TRASH_RECORD) {
            trashRepository.remove(subject);
            return "Removed stale trash metadata record: " + subject;
        }
        if (action == MetadataIssueAction.DELETE_TRASH_RECORD_ITEM) {
            TrashRecord record = trashService.deletePermanently(subject);
            return "Deleted expired trash item: " + record.originalPath();
        }
        if (action == MetadataIssueAction.DELETE_ORPHAN_TRASH_ITEM) {
            storageService.deleteTrashItemIfExists(subject);
            return "Deleted orphan trash item: " + subject;
        }
        throw new IllegalArgumentException("Unsupported trash repair action.");
    }

    private List<FileItem> joined(List<FileItem> directories, List<FileItem> files) {
        List<FileItem> items = new ArrayList<>(directories.size() + files.size());
        items.addAll(directories);
        items.addAll(files);
        return items;
    }
}
