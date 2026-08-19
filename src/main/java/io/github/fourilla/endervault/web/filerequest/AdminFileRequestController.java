package io.github.fourilla.endervault.web.filerequest;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filerequest.FileRequest;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.filerequest.UploaderNamePolicy;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminFileRequestController {

    private static final BigDecimal BYTES_PER_GIB = BigDecimal.valueOf(1024L * 1024 * 1024);

    private final FileRequestService fileRequestService;
    private final FileRequestUrlBuilder fileRequestUrlBuilder;
    private final PublicLinkTokenService publicLinkTokenService;
    private final ActivityLogService activityLogService;
    private final NasProperties.FileRequest properties;

    public AdminFileRequestController(
            FileRequestService fileRequestService,
            FileRequestUrlBuilder fileRequestUrlBuilder,
            PublicLinkTokenService publicLinkTokenService,
            ActivityLogService activityLogService,
            NasProperties nasProperties
    ) {
        this.fileRequestService = fileRequestService;
        this.fileRequestUrlBuilder = fileRequestUrlBuilder;
        this.publicLinkTokenService = publicLinkTokenService;
        this.activityLogService = activityLogService;
        this.properties = nasProperties.getFileRequest();
    }

    @GetMapping("/admin/file-requests")
    public String requests(Model model) throws IOException {
        List<FileRequestView> requests = fileRequestService.list().stream()
                .map(request -> FileRequestView.from(request, fileRequestUrlBuilder.url(request.token())))
                .toList();
        model.addAttribute("fileRequests", requests);
        model.addAttribute("fileRequestsEnabled", properties.isEnabled());
        model.addAttribute("uploaderNamePolicies", UploaderNamePolicy.values());
        model.addAttribute("defaultExpirationDays", properties.getDefaultExpirationDays());
        model.addAttribute("defaultMaxFileSizeGb", gibibytes(properties.getDefaultMaxFileSizeBytes()));
        model.addAttribute("defaultMaxTotalGb", gibibytes(properties.getDefaultMaxTotalBytes()));
        model.addAttribute("defaultMaxFiles", properties.getDefaultMaxFiles());
        model.addAttribute("defaultUploaderNamePolicy",
                UploaderNamePolicy.from(properties.getDefaultUploaderNamePolicy()));
        return "file-requests";
    }

    @PostMapping("/admin/file-requests/create")
    public Object create(
            @RequestParam String title,
            @RequestParam(value = "destinationPath", required = false) String destinationPath,
            @RequestParam UploaderNamePolicy uploaderNamePolicy,
            @RequestParam BigDecimal maxFileSizeGb,
            @RequestParam BigDecimal maxTotalGb,
            @RequestParam int maxFiles,
            @RequestParam(value = "allowedExtensions", required = false) String allowedExtensions,
            @RequestParam int expirationDays,
            @RequestParam(value = "customToken", required = false) String customToken,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileRequest fileRequest = fileRequestService.create(
                title,
                destinationPath,
                uploaderNamePolicy,
                bytes(maxFileSizeGb, "Maximum file size"),
                bytes(maxTotalGb, "Total quota"),
                maxFiles,
                allowedExtensions == null ? List.of() : List.of(allowedExtensions),
                expirationDays,
                customToken
        );
        String url = fileRequestUrlBuilder.url(fileRequest.token());
        activityLogService.record(
                "FILE_REQUEST_CREATE",
                request,
                fileRequest.destinationPath(),
                null,
                "File request created",
                metadata(fileRequest)
        );
        FlashNotification notification = FlashNotification.info("File request created.", "Copy link", url);
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                "redirect:/admin/file-requests"
        );
    }

    @PostMapping("/admin/file-requests/revoke")
    public Object revoke(
            @RequestParam String id,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileRequest fileRequest = require(id);
        fileRequestService.revoke(id);
        activityLogService.record(
                "FILE_REQUEST_REVOKE",
                request,
                fileRequest.destinationPath(),
                null,
                "File request revoked",
                metadata(fileRequest)
        );
        FlashNotification notification = FlashNotification.success("File request revoked.");
        return ActionResponseSupport.ok(
                request, redirectAttributes, notification, "redirect:/admin/file-requests"
        );
    }

    @PostMapping("/admin/file-requests/delete")
    public Object delete(
            @RequestParam String id,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        FileRequest fileRequest = require(id);
        if (fileRequest.usable(Instant.now())) {
            throw new StorageAccessException("Revoke an active file request before deleting it.");
        }
        fileRequestService.delete(id);
        activityLogService.record(
                "FILE_REQUEST_DELETE",
                request,
                fileRequest.destinationPath(),
                null,
                "File request deleted",
                metadata(fileRequest)
        );
        FlashNotification notification = FlashNotification.success("File request deleted.");
        return ActionResponseSupport.ok(
                request, redirectAttributes, notification, "redirect:/admin/file-requests"
        );
    }

    @PostMapping("/admin/file-requests/delete-expired")
    public Object deleteExpired(
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        int deleted = fileRequestService.deleteExpired(Instant.now());
        activityLogService.record(
                "FILE_REQUEST_DELETE",
                request,
                null,
                null,
                "Deleted " + deleted + " expired file requests"
        );
        FlashNotification notification = FlashNotification.success("Deleted " + deleted + " expired file requests.");
        return ActionResponseSupport.ok(
                request, redirectAttributes, notification, "redirect:/admin/file-requests"
        );
    }

    private FileRequest require(String id) throws IOException {
        return fileRequestService.require(id);
    }

    private long bytes(BigDecimal gibibytes, String label) {
        if (gibibytes == null || gibibytes.signum() <= 0) {
            throw new StorageAccessException(label + " must be greater than zero.");
        }
        try {
            return gibibytes.multiply(BYTES_PER_GIB).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException ex) {
            throw new StorageAccessException(label + " must resolve to a whole number of bytes.", ex);
        }
    }

    private String gibibytes(long bytes) {
        return BigDecimal.valueOf(bytes)
                .divide(BYTES_PER_GIB, 3, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private Map<String, String> metadata(FileRequest request) {
        return Map.of(
                "requestId", request.id(),
                "tokenFingerprint", publicLinkTokenService.fingerprint(request.token())
        );
    }

    public record FileRequestView(
            FileRequest request,
            String url,
            String destinationLabel,
            String fileLimitLabel,
            String usageLabel,
            String extensionsLabel,
            boolean active
    ) {
        static FileRequestView from(FileRequest request, String url) {
            return new FileRequestView(
                    request,
                    url,
                    request.destinationPath() == null || request.destinationPath().isBlank()
                            ? "/"
                            : "/" + request.destinationPath(),
                    ByteSizeFormatter.humanSize(request.maxFileSizeBytes()),
                    "%d / %d files, %s / %s".formatted(
                            request.acceptedFiles(),
                            request.maxFiles(),
                            ByteSizeFormatter.humanSize(request.acceptedBytes()),
                            ByteSizeFormatter.humanSize(request.maxTotalBytes())
                    ),
                    request.allowedExtensions().isEmpty()
                            ? "All extensions"
                            : String.join(", ", request.allowedExtensions()),
                    request.usable(Instant.now())
            );
        }
    }
}
