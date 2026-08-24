package io.github.fourilla.endervault.web.filerequest;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filerequest.FileRequest;
import io.github.fourilla.endervault.filerequest.FileRequestOperationsService;
import io.github.fourilla.endervault.filerequest.FileRequestOperationsService.FileRequestSnapshot;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.filerequest.UploaderNamePolicy;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.upload.ResumableUploadSession;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AdminFileRequestController {

    private static final BigDecimal BYTES_PER_GIB = BigDecimal.valueOf(1024L * 1024 * 1024);
    private static final DateTimeFormatter DATE_LABEL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final FileRequestService fileRequestService;
    private final FileRequestOperationsService operationsService;
    private final FileRequestUrlBuilder fileRequestUrlBuilder;
    private final NasProperties.FileRequest properties;

    public AdminFileRequestController(
            FileRequestService fileRequestService,
            FileRequestOperationsService operationsService,
            FileRequestUrlBuilder fileRequestUrlBuilder,
            NasProperties nasProperties
    ) {
        this.fileRequestService = fileRequestService;
        this.operationsService = operationsService;
        this.fileRequestUrlBuilder = fileRequestUrlBuilder;
        this.properties = nasProperties.getFileRequest();
    }

    @GetMapping("/admin/file-requests")
    public String requests(
            @RequestParam(value = "destinationPath", required = false) String destinationPath,
            @RequestParam(value = "copyFrom", required = false) String copyFrom,
            Model model
    ) throws IOException {
        List<FileRequestView> requests = fileRequestService.list().stream()
                .map(request -> FileRequestView.from(request, fileRequestUrlBuilder.url(request.token())))
                .toList();
        FileRequest duplicate = copyFrom == null || copyFrom.isBlank() ? null : fileRequestService.require(copyFrom);

        model.addAttribute("fileRequests", requests);
        model.addAttribute("fileRequestsEnabled", properties.isEnabled());
        model.addAttribute("customTokensEnabled", properties.isCustomTokenEnabled());
        model.addAttribute("customTokenMinLength", properties.getCustomTokenMinLength());
        model.addAttribute("customTokenMaxLength", properties.getCustomTokenMaxLength());
        model.addAttribute("uploaderNamePolicies", UploaderNamePolicy.values());
        model.addAttribute("draftTitle", duplicate == null ? "" : duplicate.title());
        model.addAttribute("draftDescription", duplicate == null ? "" : duplicate.description());
        model.addAttribute("defaultExpirationDays", duplicate == null
                ? properties.getDefaultExpirationDays() : expirationDays(duplicate));
        model.addAttribute("defaultMaxFileSizeGb", gibibytes(duplicate == null
                ? properties.getDefaultMaxFileSizeBytes() : duplicate.maxFileSizeBytes()));
        model.addAttribute("defaultMaxTotalGb", gibibytes(duplicate == null
                ? properties.getDefaultMaxTotalBytes() : duplicate.maxTotalBytes()));
        model.addAttribute("defaultMaxFiles", duplicate == null
                ? properties.getDefaultMaxFiles() : duplicate.maxFiles());
        model.addAttribute("defaultAllowedExtensions", duplicate == null
                ? "" : String.join(", ", duplicate.allowedExtensions()));
        model.addAttribute("defaultUploaderNamePolicy", duplicate == null
                ? UploaderNamePolicy.from(properties.getDefaultUploaderNamePolicy())
                : duplicate.uploaderNamePolicy());
        String requestedDestination = destinationPath;
        if ((requestedDestination == null || requestedDestination.isBlank()) && duplicate != null) {
            requestedDestination = duplicate.destinationPath();
        }
        model.addAttribute("defaultDestinationPath", fileRequestService.normalizeDestinationPath(requestedDestination));
        model.addAttribute("duplicatingRequest", duplicate);
        return "file-requests";
    }

    @GetMapping("/admin/file-requests/{id}")
    public String requestDetail(@PathVariable String id, Model model) throws IOException {
        FileRequestSnapshot snapshot = operationsService.snapshot(id);
        FileRequest request = snapshot.request();
        model.addAttribute("item", FileRequestView.from(request, fileRequestUrlBuilder.url(request.token())));
        model.addAttribute("activeUploads", snapshot.activeUploads().stream().map(UploadView::from).toList());
        model.addAttribute("pendingDecisions", snapshot.pendingDecisions().stream().map(PendingView::from).toList());
        model.addAttribute("activityHistory", snapshot.activityHistory());
        model.addAttribute("canDelete", !snapshot.hasDependencies());
        return "file-request-detail";
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

    public record FileRequestView(
            FileRequest request,
            String url,
            String destinationLabel,
            String fileLimitLabel,
            String totalLimitLabel,
            String usageLabel,
            String extensionsLabel,
            boolean active
    ) {
        static FileRequestView from(FileRequest request, String url) {
            return new FileRequestView(
                    request,
                    url,
                    request.destinationPath() == null || request.destinationPath().isBlank()
                            ? "/" : "/" + request.destinationPath(),
                    ByteSizeFormatter.humanSize(request.maxFileSizeBytes()),
                    ByteSizeFormatter.humanSize(request.maxTotalBytes()),
                    "%d / %d files, %s / %s".formatted(
                            request.acceptedFiles(), request.maxFiles(),
                            ByteSizeFormatter.humanSize(request.acceptedBytes()),
                            ByteSizeFormatter.humanSize(request.maxTotalBytes())
                    ),
                    request.allowedExtensions().isEmpty()
                            ? "All extensions" : String.join(", ", request.allowedExtensions()),
                    request.usable(Instant.now())
            );
        }
    }

    public record UploadView(ResumableUploadSession session, String sizeLabel, String createdLabel, String expiresLabel) {
        static UploadView from(ResumableUploadSession session) {
            return new UploadView(
                    session,
                    ByteSizeFormatter.humanSize(session.size()),
                    DATE_LABEL.format(session.createdAt()),
                    session.expiresAt() == null ? "Never" : DATE_LABEL.format(session.expiresAt())
            );
        }
    }

    public record PendingView(PendingFileDecision decision, String sizeLabel, String createdLabel) {
        static PendingView from(PendingFileDecision decision) {
            return new PendingView(
                    decision,
                    ByteSizeFormatter.humanSize(decision.size()),
                    DATE_LABEL.format(decision.createdAt())
            );
        }
    }
}
