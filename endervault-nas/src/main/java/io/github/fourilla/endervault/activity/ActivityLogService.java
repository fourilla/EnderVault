package io.github.fourilla.endervault.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.auth.ClientIpResolver;
import io.github.fourilla.endervault.config.NasProperties;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ActivityLogService {

    private static final Logger logger = LoggerFactory.getLogger(ActivityLogService.class);

    private final ActivityLogStore store;
    private final ActivityLogNotifier activityLogNotifier;
    private final ClientIpResolver clientIpResolver;
    private final NasProperties.ActivityLog activityLogProperties;

    public ActivityLogService(ObjectMapper objectMapper, NasProperties nasProperties) {
        this(objectMapper, nasProperties, ActivityLogNotifier.NOOP, new ClientIpResolver(nasProperties));
    }

    public ActivityLogService(
            ObjectMapper objectMapper,
            NasProperties nasProperties,
            ActivityLogNotifier activityLogNotifier
    ) {
        this(objectMapper, nasProperties, activityLogNotifier, new ClientIpResolver(nasProperties));
    }

    @Autowired
    public ActivityLogService(
            ObjectMapper objectMapper,
            NasProperties nasProperties,
            ActivityLogNotifier activityLogNotifier,
            ClientIpResolver clientIpResolver
    ) {
        this.store = new ActivityLogStore(objectMapper, nasProperties);
        this.activityLogNotifier = activityLogNotifier == null ? ActivityLogNotifier.NOOP : activityLogNotifier;
        this.clientIpResolver = clientIpResolver == null ? new ClientIpResolver(nasProperties) : clientIpResolver;
        this.activityLogProperties = nasProperties.getActivityLog();
    }

    @PostConstruct
    public void initialize() throws IOException {
        store.initialize();
    }

    public void record(
            String type,
            HttpServletRequest request,
            String path,
            String targetPath,
            String message
    ) {
        record(type, request, path, targetPath, true, message, Map.of());
    }

    public void record(
            String type,
            HttpServletRequest request,
            String path,
            String targetPath,
            String message,
            Map<String, String> metadata
    ) {
        record(type, request, path, targetPath, true, message, metadata);
    }

    public void record(
            String type,
            HttpServletRequest request,
            String path,
            String targetPath,
            boolean success,
            String message,
            Map<String, String> metadata
    ) {
        ActivityLogEntry entry = null;
        try {
            entry = append(type, request, path, targetPath, success, message, metadata);
        } catch (IOException ex) {
            logger.warn("Failed to write activity log entry.", ex);
        }
        notifyActivity(entry);
    }

    public void record(
            String type,
            String actor,
            String ip,
            String path,
            String targetPath,
            boolean success,
            String message,
            Map<String, String> metadata
    ) {
        ActivityLogEntry entry = null;
        try {
            entry = append(type, actor, ip, path, targetPath, success, message, metadata);
        } catch (IOException ex) {
            logger.warn("Failed to write activity log entry.", ex);
        }
        notifyActivity(entry);
    }

    private ActivityLogEntry append(
            String type,
            HttpServletRequest request,
            String path,
            String targetPath,
            boolean success,
            String message,
            Map<String, String> metadata
    ) throws IOException {
        if (!activityLogProperties.isEnabled()) {
            return null;
        }
        return append(
                type,
                actor(request),
                clientIpResolver.resolve(request),
                path,
                targetPath,
                success,
                message,
                metadata
        );
    }

    private synchronized ActivityLogEntry append(
            String type,
            String actor,
            String ip,
            String path,
            String targetPath,
            boolean success,
            String message,
            Map<String, String> metadata
    ) throws IOException {
        if (!activityLogProperties.isEnabled()) {
            return null;
        }

        ActivityLogEntry entry = new ActivityLogEntry(
                UUID.randomUUID().toString(),
                Instant.now(),
                type,
                blankToDefault(actor, "system"),
                blankToDefault(ip, "-"),
                blankToNull(path),
                blankToNull(targetPath),
                success,
                message,
                cleanMetadata(metadata)
        );

        store.append(entry);
        return entry;
    }

    private void notifyActivity(ActivityLogEntry entry) {
        if (entry == null) {
            return;
        }
        try {
            activityLogNotifier.notify(entry);
        } catch (RuntimeException ex) {
            logger.warn("Failed to notify activity log entry.", ex);
        }
    }

    public synchronized List<ActivityLogEntry> recentCurrentEntries(int limit) throws IOException {
        return readEntries(ActivityLogStore.CURRENT_LOG_NAME, limit);
    }

    public synchronized List<ActivityLogEntry> readEntries(String fileName, int limit) throws IOException {
        List<ActivityLogEntry> entries = readAllEntries(fileName);
        int safeLimit = Math.max(1, limit);
        int start = Math.max(0, entries.size() - safeLimit);
        List<ActivityLogEntry> recentEntries = new ArrayList<>(entries.subList(start, entries.size()));
        Collections.reverse(recentEntries);
        recentEntries.sort(Comparator.comparing(ActivityLogEntry::timestampForSort).reversed());
        return List.copyOf(recentEntries);
    }

    public synchronized ActivityLogSearchResult searchEntries(String fileName, ActivityLogQuery query) throws IOException {
        ActivityLogQuery safeQuery = query == null
                ? new ActivityLogQuery(null, null, null, null, null, null, 1, ActivityLogQuery.DEFAULT_SIZE)
                : query;
        List<ActivityLogEntry> entries = readAllEntries(fileName);
        List<String> typeOptions = entries.stream()
                .map(ActivityLogEntry::safeType)
                .distinct()
                .sorted()
                .toList();

        Comparator<ActivityLogEntry> comparator = Comparator.comparing(ActivityLogEntry::timestampForSort);
        if (safeQuery.newestFirst()) {
            comparator = comparator.reversed();
        }

        List<ActivityLogEntry> matchedEntries = entries.stream()
                .filter(safeQuery::matches)
                .sorted(comparator)
                .collect(Collectors.toList());
        int totalPages = Math.max(1, (int) Math.ceil((double) matchedEntries.size() / safeQuery.size()));
        int page = Math.min(safeQuery.page(), totalPages);
        int fromIndex = Math.min((page - 1) * safeQuery.size(), matchedEntries.size());
        int toIndex = Math.min(fromIndex + safeQuery.size(), matchedEntries.size());
        List<ActivityLogEntry> pageEntries = matchedEntries.subList(fromIndex, toIndex);

        return new ActivityLogSearchResult(
                List.copyOf(pageEntries),
                List.copyOf(typeOptions),
                entries.size(),
                matchedEntries.size(),
                page,
                safeQuery.size(),
                totalPages
        );
    }

    private List<ActivityLogEntry> readAllEntries(String fileName) throws IOException {
        return store.readAllEntries(fileName);
    }

    public synchronized List<ActivityLogFile> listLogFiles() throws IOException {
        return store.listLogFiles();
    }

    public synchronized void deleteArchive(String fileName) throws IOException {
        store.deleteArchive(fileName);
    }

    private String actor(HttpServletRequest request) {
        if (request == null) {
            return "system";
        }
        Principal principal = request.getUserPrincipal();
        return principal == null ? "anonymous" : principal.getName();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Map<String, String> cleanMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }

        Map<String, String> cleaned = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                cleaned.put(key, value);
            }
        });
        return Map.copyOf(cleaned);
    }
}
