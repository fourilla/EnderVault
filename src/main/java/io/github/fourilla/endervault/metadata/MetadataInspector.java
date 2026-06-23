package io.github.fourilla.endervault.metadata;

import io.github.fourilla.endervault.task.TaskContext;
import java.io.IOException;
import java.util.List;

public interface MetadataInspector {
    MetadataArea area();

    List<MetadataIssue> inspect() throws IOException;

    default List<MetadataIssue> inspect(TaskContext context) throws IOException {
        if (context != null) {
            context.checkCanceled();
        }
        return inspect();
    }

    String repair(MetadataIssueAction action, String subject) throws IOException;
}
