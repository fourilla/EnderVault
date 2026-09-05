package io.github.fourilla.endervault.activity;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;
import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

final class ActivityLogStore {

    static final String CURRENT_LOG_NAME = "activity-log.jsonl";

    private static final String ARCHIVE_PREFIX = "activity-log-";
    private static final String LOG_EXTENSION = ".jsonl";
    private static final DateTimeFormatter ROLLING_NAME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final ObjectMapper objectMapper;
    private final Path logDirectory;
    private final Path currentLogFile;
    private final NasProperties.ActivityLog activityLogProperties;

    ActivityLogStore(ObjectMapper objectMapper, NasProperties nasProperties) {
        this.objectMapper = objectMapper;
        this.activityLogProperties = nasProperties.getActivityLog();
        NasProperties.Storage storage = nasProperties.getStorage();
        this.logDirectory = storage.getRoot()
                .toAbsolutePath()
                .normalize()
                .resolve(storage.getMetadataDirectory())
                .resolve("logs");
        this.currentLogFile = logDirectory.resolve(CURRENT_LOG_NAME);
    }

    void initialize() throws IOException {
        Files.createDirectories(logDirectory);
    }

    synchronized void append(ActivityLogEntry entry) throws IOException {
        if (entry == null) {
            return;
        }
        Files.createDirectories(logDirectory);
        byte[] line;
        try {
            line = (objectMapper.writeValueAsString(entry) + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8);
        } catch (JacksonException ex) {
            throw new IOException("Failed to serialize activity log entry", ex);
        }
        rollIfNeeded(line.length);
        Files.write(currentLogFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    synchronized List<ActivityLogEntry> readAllEntries(String fileName) throws IOException {
        Path logFile = resolveLogFile(fileName);
        if (!Files.exists(logFile, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }

        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        List<ActivityLogEntry> entries = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            try {
                entries.add(objectMapper.readValue(line, ActivityLogEntry.class));
            } catch (JacksonException ignored) {
                // Skip malformed lines so one partial write does not hide the whole log.
            }
        }
        return List.copyOf(entries);
    }

    synchronized List<ActivityLogFile> listLogFiles() throws IOException {
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

    synchronized void deleteArchive(String fileName) throws IOException {
        if (!activityLogProperties.isAllowArchiveDelete()) {
            throw new StorageAccessException("Activity log archive deletion is disabled.");
        }
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
        if (currentSize + appendBytes <= Math.max(1024L, activityLogProperties.getMaxFileSizeBytes())) {
            return;
        }

        Path archive = nextArchiveFile();
        try {
            Files.move(currentLogFile, archive, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(currentLogFile, archive);
        }
        cleanupOldArchives();
    }

    private void cleanupOldArchives() throws IOException {
        int maxArchives = activityLogProperties.getMaxArchiveFiles();
        if (maxArchives <= 0) {
            return;
        }
        List<Path> archives;
        try (Stream<Path> paths = Files.list(logDirectory)) {
            archives = paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> isArchiveLogName(path.getFileName().toString()))
                    .sorted(Comparator.comparing(this::lastModified).reversed())
                    .toList();
        }
        for (Path archive : archives.stream().skip(maxArchives).toList()) {
            Files.deleteIfExists(archive);
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
}
