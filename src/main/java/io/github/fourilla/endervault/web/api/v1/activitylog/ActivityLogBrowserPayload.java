package io.github.fourilla.endervault.web.api.v1.activitylog;

import io.github.fourilla.endervault.activity.ActivityLogEntry;
import io.github.fourilla.endervault.activity.ActivityLogFile;
import io.github.fourilla.endervault.activity.ActivityLogQuery;
import io.github.fourilla.endervault.activity.ActivityLogSearchResult;
import java.util.List;

public record ActivityLogBrowserPayload(
        List<LogFilePayload> files,
        String selectedFile,
        boolean selectedFileDeletable,
        List<EntryPayload> entries,
        List<String> typeOptions,
        int totalCount,
        int matchedCount,
        int firstIndex,
        int lastIndex,
        int page,
        int size,
        int totalPages,
        List<Integer> pageSizeOptions,
        QueryPayload query
) {

    public ActivityLogBrowserPayload {
        files = List.copyOf(files);
        entries = List.copyOf(entries);
        typeOptions = List.copyOf(typeOptions);
        pageSizeOptions = List.copyOf(pageSizeOptions);
    }

    public static ActivityLogBrowserPayload from(
            List<ActivityLogFile> files,
            String selectedFile,
            ActivityLogFile selectedLogFile,
            ActivityLogSearchResult result,
            List<Integer> pageSizeOptions,
            ActivityLogQuery query
    ) {
        return new ActivityLogBrowserPayload(
                files.stream().map(LogFilePayload::from).toList(),
                selectedFile,
                selectedLogFile != null && selectedLogFile.deletable(),
                result.entries().stream().map(EntryPayload::from).toList(),
                result.typeOptions(),
                result.totalCount(),
                result.matchedCount(),
                result.firstIndex(),
                result.lastIndex(),
                result.page(),
                result.size(),
                result.totalPages(),
                pageSizeOptions,
                QueryPayload.from(query, result)
        );
    }

    public record LogFilePayload(
            String name,
            String label,
            String sizeLabel,
            String modifiedLabel,
            boolean current,
            boolean deletable
    ) {

        private static LogFilePayload from(ActivityLogFile file) {
            return new LogFilePayload(
                    file.name(),
                    file.label(),
                    file.sizeLabel(),
                    file.modifiedLabel(),
                    file.current(),
                    file.deletable()
            );
        }
    }

    public record EntryPayload(
            String id,
            String timestampLabel,
            String statusLabel,
            String statusClass,
            String type,
            String actorLabel,
            String ipLabel,
            String messageLabel,
            String pathLabel,
            String targetPathLabel,
            String metadataLabel,
            String detailLine
    ) {

        private static EntryPayload from(ActivityLogEntry entry) {
            return new EntryPayload(
                    entry.id() == null || entry.id().isBlank() ? "-" : entry.id(),
                    entry.timestampLabel(),
                    entry.statusLabel(),
                    entry.statusClass(),
                    entry.safeType(),
                    entry.actorLabel(),
                    entry.ipLabel(),
                    entry.messageLabel(),
                    entry.pathLabel(),
                    entry.targetPathLabel(),
                    entry.metadataLabel(),
                    entry.detailLine()
            );
        }
    }

    public record QueryPayload(
            String text,
            String type,
            String status,
            String order,
            String from,
            String to,
            int page,
            int size
    ) {

        private static QueryPayload from(ActivityLogQuery query, ActivityLogSearchResult result) {
            return new QueryPayload(
                    query.text(),
                    query.type(),
                    query.status(),
                    query.order(),
                    query.from(),
                    query.to(),
                    result.page(),
                    result.size()
            );
        }
    }
}
