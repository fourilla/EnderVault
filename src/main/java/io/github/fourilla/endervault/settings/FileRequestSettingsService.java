package io.github.fourilla.endervault.settings;

import io.github.fourilla.endervault.config.LocalPropertiesFile;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.filerequest.UploaderNamePolicy;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

@Service
public class FileRequestSettingsService {

    private static final BigDecimal BYTES_PER_GIB = BigDecimal.valueOf(1024L * 1024 * 1024);
    private static final int MAX_TOKEN_LENGTH = 256;

    private final NasProperties nasProperties;
    private final LocalPropertiesFile localPropertiesFile;

    public FileRequestSettingsService(NasProperties nasProperties, LocalPropertiesFile localPropertiesFile) {
        this.nasProperties = nasProperties;
        this.localPropertiesFile = localPropertiesFile;
    }

    public FileRequestSettingsSnapshot currentSettings() {
        NasProperties.FileRequest settings = nasProperties.getFileRequest();
        return new FileRequestSettingsSnapshot(
                settings.isEnabled(),
                settings.getDefaultExpirationDays(),
                gibibytes(settings.getDefaultMaxFileSizeBytes()),
                gibibytes(settings.getDefaultMaxTotalBytes()),
                settings.getDefaultMaxFiles(),
                UploaderNamePolicy.from(settings.getDefaultUploaderNamePolicy()),
                settings.isCustomTokenEnabled(),
                settings.getCustomTokenMinLength(),
                settings.getCustomTokenMaxLength(),
                settings.getRandomTokenBytes(),
                settings.getMaxConcurrentUploadsPerRequest(),
                settings.isRateLimitEnabled(),
                settings.getRateLimitMaxAdmissions(),
                settings.getRateLimitWindowSeconds(),
                settings.getAccessLogDedupSeconds(),
                localPropertiesFile.configFile().toString()
        );
    }

    public FileRequestSettingsUpdate updateFrom(MultiValueMap<String, String> parameters) {
        long maxFileSizeBytes = bytes(first(parameters, "defaultMaxFileSizeGb"), "Default maximum file size");
        long maxTotalBytes = bytes(first(parameters, "defaultMaxTotalGb"), "Default total quota");
        if (maxFileSizeBytes > FileRequestService.HARD_MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("Default maximum file size cannot exceed 20 GiB.");
        }
        if (maxTotalBytes > FileRequestService.HARD_MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("Default total quota cannot exceed 100 GiB.");
        }
        if (maxTotalBytes < maxFileSizeBytes) {
            throw new IllegalArgumentException("Default total quota cannot be smaller than the per-file limit.");
        }

        int tokenMinLength = intRange(first(parameters, "customTokenMinLength"), 1, MAX_TOKEN_LENGTH,
                "Custom token minimum length");
        int tokenMaxLength = intRange(first(parameters, "customTokenMaxLength"), 1, MAX_TOKEN_LENGTH,
                "Custom token maximum length");
        if (tokenMaxLength < tokenMinLength) {
            throw new IllegalArgumentException("Custom token maximum length cannot be smaller than its minimum length.");
        }

        return new FileRequestSettingsUpdate(
                parameters.containsKey("enabled"),
                intRange(first(parameters, "defaultExpirationDays"), 0,
                        FileRequestService.HARD_MAX_EXPIRATION_DAYS, "Default expiration"),
                maxFileSizeBytes,
                maxTotalBytes,
                intRange(first(parameters, "defaultMaxFiles"), 1,
                        FileRequestService.HARD_MAX_FILES, "Default maximum files"),
                uploaderNamePolicy(first(parameters, "defaultUploaderNamePolicy")),
                parameters.containsKey("customTokenEnabled"),
                tokenMinLength,
                tokenMaxLength,
                intRange(first(parameters, "randomTokenBytes"), 8, 64, "Random token bytes"),
                intRange(first(parameters, "maxConcurrentUploadsPerRequest"), 1, 2,
                        "Concurrent uploads per request"),
                parameters.containsKey("rateLimitEnabled"),
                intRange(first(parameters, "rateLimitMaxAdmissions"), 1, 100_000,
                        "Maximum admissions"),
                intRange(first(parameters, "rateLimitWindowSeconds"), 1, 86_400,
                        "Admission window"),
                intRange(first(parameters, "accessLogDedupSeconds"), 0, 86_400,
                        "Access log deduplication window")
        );
    }

    public void save(FileRequestSettingsUpdate update) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("nas.file-request.enabled", Boolean.toString(update.enabled()));
        values.put("nas.file-request.default-expiration-days", Integer.toString(update.defaultExpirationDays()));
        values.put("nas.file-request.default-max-file-size-bytes", Long.toString(update.defaultMaxFileSizeBytes()));
        values.put("nas.file-request.default-max-total-bytes", Long.toString(update.defaultMaxTotalBytes()));
        values.put("nas.file-request.default-max-files", Integer.toString(update.defaultMaxFiles()));
        values.put("nas.file-request.default-uploader-name-policy",
                update.defaultUploaderNamePolicy().name().toLowerCase());
        values.put("nas.file-request.custom-token-enabled", Boolean.toString(update.customTokenEnabled()));
        values.put("nas.file-request.custom-token-min-length", Integer.toString(update.customTokenMinLength()));
        values.put("nas.file-request.custom-token-max-length", Integer.toString(update.customTokenMaxLength()));
        values.put("nas.file-request.random-token-bytes", Integer.toString(update.randomTokenBytes()));
        values.put("nas.file-request.max-concurrent-uploads-per-request",
                Integer.toString(update.maxConcurrentUploadsPerRequest()));
        values.put("nas.file-request.rate-limit-enabled", Boolean.toString(update.rateLimitEnabled()));
        values.put("nas.file-request.rate-limit-max-admissions", Integer.toString(update.rateLimitMaxAdmissions()));
        values.put("nas.file-request.rate-limit-window-seconds", Integer.toString(update.rateLimitWindowSeconds()));
        values.put("nas.file-request.access-log-dedup-seconds", Integer.toString(update.accessLogDedupSeconds()));
        localPropertiesFile.update(values, "# File request settings managed from EnderVault Settings.");
        applyToRuntime(update);
    }

    private void applyToRuntime(FileRequestSettingsUpdate update) {
        NasProperties.FileRequest settings = nasProperties.getFileRequest();
        settings.setEnabled(update.enabled());
        settings.setDefaultExpirationDays(update.defaultExpirationDays());
        settings.setDefaultMaxFileSizeBytes(update.defaultMaxFileSizeBytes());
        settings.setDefaultMaxTotalBytes(update.defaultMaxTotalBytes());
        settings.setDefaultMaxFiles(update.defaultMaxFiles());
        settings.setDefaultUploaderNamePolicy(update.defaultUploaderNamePolicy().name().toLowerCase());
        settings.setCustomTokenEnabled(update.customTokenEnabled());
        settings.setCustomTokenMinLength(update.customTokenMinLength());
        settings.setCustomTokenMaxLength(update.customTokenMaxLength());
        settings.setRandomTokenBytes(update.randomTokenBytes());
        settings.setMaxConcurrentUploadsPerRequest(update.maxConcurrentUploadsPerRequest());
        settings.setRateLimitEnabled(update.rateLimitEnabled());
        settings.setRateLimitMaxAdmissions(update.rateLimitMaxAdmissions());
        settings.setRateLimitWindowSeconds(update.rateLimitWindowSeconds());
        settings.setAccessLogDedupSeconds(update.accessLogDedupSeconds());
    }

    private static UploaderNamePolicy uploaderNamePolicy(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Default uploader name policy is required.");
        }
        try {
            return UploaderNamePolicy.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Default uploader name policy is invalid.");
        }
    }

    private static long bytes(String rawValue, String label) {
        try {
            BigDecimal gibibytes = new BigDecimal(clean(rawValue));
            if (gibibytes.signum() <= 0) {
                throw new IllegalArgumentException(label + " must be greater than zero.");
            }
            return gibibytes.multiply(BYTES_PER_GIB)
                    .setScale(0, RoundingMode.UNNECESSARY)
                    .longValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new IllegalArgumentException(label + " must resolve to a whole number of bytes.");
        }
    }

    private static int intRange(String rawValue, int min, int max, String label) {
        try {
            int value = Integer.parseInt(clean(rawValue));
            if (value < min || value > max) {
                throw new IllegalArgumentException(label + " must be between " + min + " and " + max + ".");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be a number.");
        }
    }

    private static String first(MultiValueMap<String, String> parameters, String key) {
        String value = parameters.getFirst(key);
        return value == null ? "" : value;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace("\r", "").replace("\n", "").trim();
    }

    private static String gibibytes(long bytes) {
        return BigDecimal.valueOf(bytes)
                .divide(BYTES_PER_GIB, 3, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    public record FileRequestSettingsSnapshot(
            boolean enabled,
            int defaultExpirationDays,
            String defaultMaxFileSizeGb,
            String defaultMaxTotalGb,
            int defaultMaxFiles,
            UploaderNamePolicy defaultUploaderNamePolicy,
            boolean customTokenEnabled,
            int customTokenMinLength,
            int customTokenMaxLength,
            int randomTokenBytes,
            int maxConcurrentUploadsPerRequest,
            boolean rateLimitEnabled,
            int rateLimitMaxAdmissions,
            int rateLimitWindowSeconds,
            int accessLogDedupSeconds,
            String configPath
    ) {
    }

    public record FileRequestSettingsUpdate(
            boolean enabled,
            int defaultExpirationDays,
            long defaultMaxFileSizeBytes,
            long defaultMaxTotalBytes,
            int defaultMaxFiles,
            UploaderNamePolicy defaultUploaderNamePolicy,
            boolean customTokenEnabled,
            int customTokenMinLength,
            int customTokenMaxLength,
            int randomTokenBytes,
            int maxConcurrentUploadsPerRequest,
            boolean rateLimitEnabled,
            int rateLimitMaxAdmissions,
            int rateLimitWindowSeconds,
            int accessLogDedupSeconds
    ) {
    }
}
