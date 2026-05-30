package io.github.fourilla.endervault.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.auth.ClientIpResolver;
import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.Principal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ActivityLogService {

    private static final Logger logger = LoggerFactory.getLogger(ActivityLogService.class);
    private static final String CURRENT_LOG_NAME = "activity-log.jsonl";
    private static final String ARCHIVE_PREFIX = "activity-log-";
    private static final String LOG_EXTENSION = ".jsonl";
    private static final long MAX_LOG_BYTES = 10L * 1024L * 1024L;
    private static final DateTimeFormatter ROLLING_NAME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final ObjectMapper objectMapper;
    private final Path logDirectory;
    private final Path currentLogFile;

    public ActivityLogService(ObjectMapper objectMapper, NasProperties nasProperties) {
        this.objectMapper = objectMapper;
        NasProperties.Storage storage = nasProperties.getStorage();
        this.logDirectory = storage.getRoot()
                .toAbsolutePath()
                .normalize()
                .resolve(storage.getMetadataDirectory())
                .resolve("logs");
        this.currentLogFile = logDirectory.resolve(CURRENT_LOG_NAME);
    }

    @PostConstruct
    public void initialize() throws IOException {
        Files.createDirectories(logDirectory);
    }

    public synchronized void record(
            String type,
            HttpServletRequest request,
            String path,
            String targetPath,
            String message
    ) {
        record(type, request, path, targetPath, true, message, Map.of());
    }

    public synchronized void record(
            String type,
            HttpServletRequest request,
            String path,
            String targetPath,
            String message,
            Map<String, String> metadata
    ) {
        record(type, request, path, targetPath, true, message, metadata);
    }

    public synchronized void record(
            String type,
            HttpServletRequest request,
            String path,
            String targetPath,
            boolean success,
            String message,
            Map<String, String> metadata
    ) {
        try {
            append(type, request, path, targetPath, success, message, metadata);
        } catch (IOException ex) {
            logger.warn("Failed to write activity log entry.", ex);
        }
    }

    private void append(
            String type,
            HttpServletRequest request,
            String path,
            String targetPath,
            boolean success,
            String message,
            Map<String, String> metadata
    ) throws IOException {
        Files.createDirectories(logDirectory);

        ActivityLogEntry entry = new ActivityLogEntry(
                UUID.randomUUID().toString(),
                Instant.now(),
                type,
                actor(request),
                ClientIpResolver.resolve(request),
                blankToNull(path),
                blankToNull(targetPath),
                success,
                message,
                cleanMetadata(metadata)
        );

        byte[] line = (objectMapper.writeValueAsString(entry) + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        rollIfNeeded(line.length);
        Files.write(currentLogFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized List<ActivityLogEntry> recentCurrentEntries(int limit) throws IOException {
        return readEntries(CURRENT_LOG_NAME, limit);
    }

    public synchronized List<ActivityLogEntry> readEntries(String fileName, int limit) throws IOException {
        Path logFile = resolveLogFile(fileName);
        if (!Files.exists(logFile, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }

        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        List<ActivityLogEntry> entries = new ArrayList<>();
        int start = Math.max(0, lines.size() - Math.max(1, limit));
        for (int i = lines.size() - 1; i >= start; i--) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            try {
                entries.add(objectMapper.readValue(line, ActivityLogEntry.class));
            } catch (IOException ignored) {
                // Skip malformed lines so one partial write does not hide the whole log.
            }
        }
        return List.copyOf(entries);
    }

    public synchronized List<ActivityLogFile> listLogFiles() throws IOException {
        Files.createDirectories(logDirectory);
        List<ActivityLogFile> files = new ArrayList<>();
        if (Files.exists(currentLogFile, LinkOption.NOFOLLOW_LINKS)) {
            files.add(toLogFile(currentLogFile, true));
        } else {
            files.add(new ActivityLogFile(CURRENT_LOG_NAME, true, 0L, "0 B", null));
        }

        try (Stream<Path> paths = Files.list(logDirectory)) {
            paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> isArchiveLogName(path.getFileName().toString()))
                    .sorted(Comparator.comparing(this::lastModified).reversed())
                    .forEach(path -> files.add(toLogFile(path, false)));
        }
        return List.copyOf(files);
    }

    public synchronized void deleteArchive(String fileName) throws IOException {
        if (!isArchiveLogName(fileName)) {
            throw new StorageAccessException("Only archived activity logs can be deleted.");
        }
        Files.deleteIfExists(resolveLogFile(fileName));
    }

    private void rollIfNeeded(int appendBytes) throws IOException {
        if (!Files.exists(currentLogFile, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        long currentSize = Files.size(currentLogFile);
        if (currentSize + appendBytes <= MAX_LOG_BYTES) {
            return;
        }

        Path archive = nextArchiveFile();
        try {
            Files.move(currentLogFile, archive, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(currentLogFile, archive);
        }
    }

    private Path nextArchiveFile() {
        String baseName = ARCHIVE_PREFIX + ROLLING_NAME_FORMATTER.format(Instant.now());
        Path archive = logDirectory.resolve(baseName + LOG_EXTENSION);
        int index = 1;
        while (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
            archive = logDirectory.resolve(baseName + "-" + index + LOG_EXTENSION);
            index++;
        }
        return archive;
    }

    private Path resolveLogFile(String fileName) {
        String safeName = fileName == null || fileName.isBlank() ? CURRENT_LOG_NAME : fileName;
        if (!CURRENT_LOG_NAME.equals(safeName) && !isArchiveLogName(safeName)) {
            throw new StorageAccessException("Invalid activity log file.");
        }

        Path file = logDirectory.resolve(safeName).normalize();
        if (!file.startsWith(logDirectory)) {
            throw new StorageAccessException("Invalid activity log file.");
        }
        return file;
    }

    private ActivityLogFile toLogFile(Path path, boolean current) {
        try {
            long size = Files.size(path);
            return new ActivityLogFile(
                    path.getFileName().toString(),
                    current,
                    size,
                    ByteSizeFormatter.humanSize(size),
                    lastModified(path)
            );
        } catch (IOException ex) {
            return new ActivityLogFile(path.getFileName().toString(), current, 0L, "0 B", null);
        }
    }

    private Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException ex) {
            return Instant.EPOCH;
        }
    }

    private boolean isArchiveLogName(String fileName) {
        return fileName != null
                && fileName.startsWith(ARCHIVE_PREFIX)
                && fileName.endsWith(LOG_EXTENSION)
                && !fileName.contains("/")
                && !fileName.contains("\\");
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
