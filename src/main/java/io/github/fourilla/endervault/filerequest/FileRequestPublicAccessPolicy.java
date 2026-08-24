package io.github.fourilla.endervault.filerequest;

import io.github.fourilla.endervault.auth.ClientIpResolver;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.upload.ResumableUploadRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class FileRequestPublicAccessPolicy {

    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final ClientIpResolver clientIpResolver;
    private final NasProperties.FileRequest properties;
    private final Map<ClientKey, AdmissionBucket> admissionBuckets = accessOrderedMap();
    private final Map<ClientKey, Instant> recentAccessLogs = accessOrderedMap();

    public FileRequestPublicAccessPolicy(ClientIpResolver clientIpResolver, NasProperties nasProperties) {
        this.clientIpResolver = clientIpResolver;
        this.properties = nasProperties.getFileRequest();
    }

    public synchronized void requireNewUploadAdmission(String requestId, HttpServletRequest request) {
        if (!properties.isRateLimitEnabled()) {
            return;
        }
        Instant now = Instant.now();
        ClientKey key = key(requestId, request);
        AdmissionBucket bucket = admissionBuckets.get(key);
        if (bucket == null) {
            ensureCapacity(admissionBuckets);
            bucket = new AdmissionBucket(properties.getRateLimitMaxAdmissions(), now);
            admissionBuckets.put(key, bucket);
        }

        double maximum = properties.getRateLimitMaxAdmissions();
        double refillPerSecond = maximum / properties.getRateLimitWindowSeconds();
        double elapsedSeconds = Math.max(0.0, Duration.between(bucket.updatedAt(), now).toNanos() / 1_000_000_000.0);
        double available = Math.min(maximum, bucket.tokens() + elapsedSeconds * refillPerSecond);
        if (available < 1.0) {
            int retryAfter = Math.max(1, (int) Math.ceil((1.0 - available) / refillPerSecond));
            admissionBuckets.put(key, new AdmissionBucket(available, now));
            throw new ResumableUploadRejectedException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many upload sessions were requested. Retry shortly.",
                    retryAfter
            );
        }
        admissionBuckets.put(key, new AdmissionBucket(available - 1.0, now));
    }

    public synchronized boolean shouldRecordPageAccess(String requestId, HttpServletRequest request) {
        int dedupSeconds = properties.getAccessLogDedupSeconds();
        if (dedupSeconds <= 0) {
            return true;
        }
        Instant now = Instant.now();
        ClientKey key = key(requestId, request);
        Instant suppressUntil = recentAccessLogs.get(key);
        if (suppressUntil != null && suppressUntil.isAfter(now)) {
            return false;
        }
        ensureCapacity(recentAccessLogs);
        recentAccessLogs.put(key, now.plusSeconds(dedupSeconds));
        return true;
    }

    private ClientKey key(String requestId, HttpServletRequest request) {
        return new ClientKey(requestId, clientIpResolver.resolve(request));
    }

    private <T> void ensureCapacity(Map<ClientKey, T> values) {
        if (values.size() < MAX_TRACKED_CLIENTS) {
            return;
        }
        Iterator<ClientKey> iterator = values.keySet().iterator();
        if (iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static <T> Map<ClientKey, T> accessOrderedMap() {
        return new LinkedHashMap<>(16, 0.75f, true);
    }

    private record ClientKey(String requestId, String clientIp) {
    }

    private record AdmissionBucket(double tokens, Instant updatedAt) {
    }
}
