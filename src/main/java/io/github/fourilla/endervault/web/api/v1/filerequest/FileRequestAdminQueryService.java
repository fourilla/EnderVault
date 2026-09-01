package io.github.fourilla.endervault.web.api.v1.filerequest;

import io.github.fourilla.endervault.activity.ActivityLogEntry;
import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filerequest.FileRequest;
import io.github.fourilla.endervault.filerequest.FileRequestOperationsService;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.filerequest.UploaderNamePolicy;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.upload.ResumableUploadSession;
import io.github.fourilla.endervault.web.api.v1.filerequest.FileRequestAdminPayloads.ActiveUpload;
import io.github.fourilla.endervault.web.api.v1.filerequest.FileRequestAdminPayloads.ActivityItem;
import io.github.fourilla.endervault.web.api.v1.filerequest.FileRequestAdminPayloads.CreateDefaults;
import io.github.fourilla.endervault.web.api.v1.filerequest.FileRequestAdminPayloads.DetailPayload;
import io.github.fourilla.endervault.web.api.v1.filerequest.FileRequestAdminPayloads.ListPayload;
import io.github.fourilla.endervault.web.api.v1.filerequest.FileRequestAdminPayloads.PendingFile;
import io.github.fourilla.endervault.web.api.v1.filerequest.FileRequestAdminPayloads.RequestItem;
import io.github.fourilla.endervault.web.api.v1.filerequest.FileRequestAdminPayloads.UploaderPolicy;
import io.github.fourilla.endervault.web.filerequest.FileRequestUrlBuilder;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import org.springframework.stereotype.Service;

@Service
public class FileRequestAdminQueryService {

    private static final BigDecimal BYTES_PER_GIB = BigDecimal.valueOf(1024L * 1024 * 1024);
    private static final DateTimeFormatter DATE_LABEL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final FileRequestService fileRequestService;
    private final FileRequestOperationsService operationsService;
    private final FileRequestUrlBuilder urlBuilder;
    private final NasProperties.FileRequest properties;

    public FileRequestAdminQueryService(
            FileRequestService fileRequestService,
            FileRequestOperationsService operationsService,
            FileRequestUrlBuilder urlBuilder,
            NasProperties nasProperties
    ) {
        this.fileRequestService = fileRequestService;
        this.operationsService = operationsService;
        this.urlBuilder = urlBuilder;
        this.properties = nasProperties.getFileRequest();
    }

    public ListPayload list(String destinationPath, String copyFrom) throws IOException {
        FileRequest duplicate = copyFrom == null || copyFrom.isBlank() ? null : fileRequestService.require(copyFrom);
        String requestedDestination = destinationPath;
        if ((requestedDestination == null || requestedDestination.isBlank()) && duplicate != null) {
            requestedDestination = duplicate.destinationPath();
        }
        CreateDefaults defaults = new CreateDefaults(
                duplicate == null ? "" : duplicate.title(),
                duplicate == null ? "" : duplicate.description(),
                fileRequestService.normalizeDestinationPath(requestedDestination),
                duplicate == null ? properties.getDefaultExpirationDays() : expirationDays(duplicate),
                gibibytes(duplicate == null ? properties.getDefaultMaxFileSizeBytes() : duplicate.maxFileSizeBytes()),
                gibibytes(duplicate == null ? properties.getDefaultMaxTotalBytes() : duplicate.maxTotalBytes()),
                duplicate == null ? properties.getDefaultMaxFiles() : duplicate.maxFiles(),
                duplicate == null ? "" : String.join(", ", duplicate.allowedExtensions()),
                (duplicate == null
                        ? UploaderNamePolicy.from(properties.getDefaultUploaderNamePolicy())
                        : duplicate.uploaderNamePolicy()).name(),
                duplicate != null
        );
        return new ListPayload(
                fileRequestService.list().stream().map(this::item).toList(),
                properties.isEnabled(),
                properties.isCustomTokenEnabled(),
                properties.getCustomTokenMinLength(),
                properties.getCustomTokenMaxLength(),
                Arrays.stream(UploaderNamePolicy.values())
                        .map(policy -> new UploaderPolicy(policy.name(), policy.label()))
                        .toList(),
                defaults
        );
    }

    public DetailPayload detail(String id) throws IOException {
        FileRequestOperationsService.FileRequestSnapshot snapshot = operationsService.snapshot(id);
        return new DetailPayload(
                item(snapshot.request()),
                snapshot.activeUploads().stream().map(this::activeUpload).toList(),
                snapshot.pendingDecisions().stream().map(this::pendingFile).toList(),
                snapshot.activityHistory().stream().map(this::activityItem).toList(),
                !snapshot.hasDependencies()
        );
    }

    private RequestItem item(FileRequest request) {
        String extensionsLabel = request.allowedExtensions().isEmpty()
                ? "All extensions" : String.join(", ", request.allowedExtensions());
        return new RequestItem(
                request.id(), request.title(), request.description(), urlBuilder.url(request.token()),
                request.destinationPath(),
                request.destinationPath() == null || request.destinationPath().isBlank()
                        ? "/" : "/" + request.destinationPath(),
                request.uploaderNamePolicy().name(), request.uploaderNamePolicy().label(),
                request.maxFileSizeBytes(), ByteSizeFormatter.humanSize(request.maxFileSizeBytes()),
                request.maxTotalBytes(), ByteSizeFormatter.humanSize(request.maxTotalBytes()),
                request.maxFiles(), request.allowedExtensions(), extensionsLabel,
                request.acceptedBytes(), request.acceptedFiles(),
                "%d / %d files, %s / %s".formatted(
                        request.acceptedFiles(), request.maxFiles(),
                        ByteSizeFormatter.humanSize(request.acceptedBytes()),
                        ByteSizeFormatter.humanSize(request.maxTotalBytes())
                ),
                request.createdLabel(), request.expiresLabel(), request.statusLabel(), request.statusClass(),
                request.usable(Instant.now())
        );
    }

    private ActiveUpload activeUpload(ResumableUploadSession session) {
        return new ActiveUpload(
                session.id(), session.originalFilename(), blankToDash(session.submittedBy()),
                ByteSizeFormatter.humanSize(session.size()), session.status().name(),
                DATE_LABEL.format(session.createdAt()),
                session.expiresAt() == null ? "Never" : DATE_LABEL.format(session.expiresAt())
        );
    }

    private PendingFile pendingFile(PendingFileDecision decision) {
        return new PendingFile(
                decision.id(), decision.originalFilename(), blankToDash(decision.submittedBy()),
                ByteSizeFormatter.humanSize(decision.size()), DATE_LABEL.format(decision.createdAt())
        );
    }

    private ActivityItem activityItem(ActivityLogEntry entry) {
        return new ActivityItem(
                entry.id(), entry.timestampLabel(), entry.typeLabel(), entry.ipLabel(), entry.messageLabel()
        );
    }

    private int expirationDays(FileRequest request) {
        if (request.expiresAt() == null) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(Duration.between(request.createdAt(), request.expiresAt()).toHours() / 24.0));
    }

    private String gibibytes(long bytes) {
        return BigDecimal.valueOf(bytes)
                .divide(BYTES_PER_GIB, 3, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
