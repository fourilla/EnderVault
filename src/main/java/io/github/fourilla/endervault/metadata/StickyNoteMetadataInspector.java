package io.github.fourilla.endervault.metadata;

import io.github.fourilla.endervault.stickynote.StickyNote;
import io.github.fourilla.endervault.stickynote.StickyNoteService;
import io.github.fourilla.endervault.task.TaskContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class StickyNoteMetadataInspector implements MetadataInspector {

    private final StickyNoteService stickyNoteService;

    public StickyNoteMetadataInspector(StickyNoteService stickyNoteService) {
        this.stickyNoteService = stickyNoteService;
    }

    @Override
    public MetadataArea area() {
        return MetadataArea.STICKY_NOTES;
    }

    @Override
    public List<MetadataIssue> inspect() throws IOException {
        return inspect(null);
    }

    @Override
    public List<MetadataIssue> inspect(TaskContext context) throws IOException {
        List<MetadataIssue> issues = new ArrayList<>();
        for (StickyNote note : stickyNoteService.listAll()) {
            if (context != null) {
                context.checkCanceled();
            }
            if (!stickyNoteService.targetExists(note.context())) {
                issues.add(new MetadataIssue(
                        area(),
                        MetadataIssueSeverity.WARNING,
                        MetadataIssueAction.REMOVE_METADATA,
                        note.id(),
                        "Sticky note target is missing",
                        note.summary() + " - " + stickyNoteService.contextLabel(note.context()),
                        "Remove this orphan sticky note metadata."
                ));
            }
        }
        return List.copyOf(issues);
    }

    @Override
    public String repair(MetadataIssueAction action, String subject) throws IOException {
        if (action != MetadataIssueAction.REMOVE_METADATA) {
            throw new IllegalArgumentException("Unsupported sticky note repair action.");
        }
        stickyNoteService.delete(subject);
        return "Removed orphan sticky note metadata: " + subject;
    }
}
