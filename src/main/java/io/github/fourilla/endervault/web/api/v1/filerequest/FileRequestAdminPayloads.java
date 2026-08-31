package io.github.fourilla.endervault.web.api.v1.filerequest;

import java.util.List;

public final class FileRequestAdminPayloads {

    private FileRequestAdminPayloads() {
    }

    public record ListPayload(
            List<RequestItem> requests,
            boolean enabled,
            boolean customTokensEnabled,
            int customTokenMinLength,
            int customTokenMaxLength,
            List<UploaderPolicy> uploaderNamePolicies,
            CreateDefaults defaults
    ) {
    }

    public record DetailPayload(
            RequestItem item,
            List<ActiveUpload> activeUploads,
            List<PendingFile> pendingDecisions,
            List<ActivityItem> activityHistory,
            boolean canDelete
    ) {
    }

    public record RequestItem(
            String id,
            String title,
            String description,
            String url,
            String destinationPath,
            String destinationLabel,
            String uploaderNamePolicy,
            String uploaderNameLabel,
            long maxFileSizeBytes,
            String fileLimitLabel,
            long maxTotalBytes,
            String totalLimitLabel,
            int maxFiles,
            List<String> allowedExtensions,
            String extensionsLabel,
            long acceptedBytes,
            int acceptedFiles,
            String usageLabel,
            String createdLabel,
            String expiresLabel,
            String statusLabel,
            String statusClass,
            boolean active
    ) {
    }

    public record CreateDefaults(
            String title,
            String description,
            String destinationPath,
            int expirationDays,
            String maxFileSizeGb,
            String maxTotalGb,
            int maxFiles,
            String allowedExtensions,
            String uploaderNamePolicy,
            boolean duplicating
    ) {
    }

    public record UploaderPolicy(String value, String label) {
    }

    public record ActiveUpload(
            String id,
            String originalFilename,
            String submittedBy,
            String sizeLabel,
            String status,
            String createdLabel,
            String expiresLabel
    ) {
    }

    public record PendingFile(
            String id,
            String originalFilename,
            String submittedBy,
            String sizeLabel,
            String createdLabel
    ) {
    }

    public record ActivityItem(
            String id,
            String timestampLabel,
            String typeLabel,
            String ipLabel,
            String messageLabel
    ) {
    }
}
