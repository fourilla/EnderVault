package io.github.fourilla.endervault.recent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.SortDirection;
import io.github.fourilla.endervault.storage.StorageService;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class RecentService {

    private static final TypeReference<List<RecentItem>> RECENT_LIST = new TypeReference<>() {
    };

    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final Path registryFile;
    private final NasProperties.Recent recentProperties;

    public RecentService(StorageService storageService, ObjectMapper objectMapper, NasProperties nasProperties) {
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.recentProperties = nasProperties.getRecent();
        this.registryFile = nasProperties.getStorage().getRoot()
                .toAbsolutePath()
                .normalize()
                .resolve(nasProperties.getStorage().getMetadataDirectory())
                .resolve("recent-items.json");
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        Files.createDirectories(registryFile.getParent());
        if (!Files.exists(registryFile)) {
            writeAll(List.of());
        }
    }

    public synchronized void recordVaultPath(String vaultPath) {
        try {
            record(vaultPath);
        } catch (IOException | StorageAccessException ignored) {
            // Recent history should never break the primary file operation.
        }
    }

    public synchronized List<RecentItem> storedItems() throws IOException {
        return List.copyOf(readAllMutable());
    }

    public synchronized List<RecentListItem> list(String query, RecentSort sort, SortDirection direction)
            throws IOException {
        List<RecentItem> records = readAllMutable();
        List<RecentItem> existingRecords = new ArrayList<>();
        List<RecentListItem> items = new ArrayList<>();

        for (RecentItem record : records) {
            try {
                FileItem item = storageService.describeVaultPath(record.path());
                existingRecords.add(record);
                items.add(new RecentListItem(item, record.lastAccessedAt()));
            } catch (IOException | StorageAccessException ignored) {
                // Stale records are removed below.
            }
        }

        if (existingRecords.size() != records.size()) {
            writeAll(existingRecords);
        }

        String normalizedQuery = normalizeQuery(query);
        Comparator<RecentListItem> comparator = comparator(sort);
        if (direction == SortDirection.DESC) {
            comparator = comparator.reversed();
        }

        return items.stream()
                .filter(item -> matches(item, normalizedQuery))
                .sorted(comparator)
                .toList();
    }

    public synchronized void remove(String vaultPath) throws IOException {
        removeAll(List.of(vaultPath));
    }

    public synchronized void removeAll(List<String> vaultPaths) throws IOException {
        if (vaultPaths == null || vaultPaths.isEmpty()) {
            return;
        }

        List<RecentItem> records = readAllMutable();
        boolean changed = records.removeIf(record -> vaultPaths.contains(record.path()));
        if (changed) {
            writeAll(records);
        }
    }

    public synchronized void clear() throws IOException {
        writeAll(List.of());
    }

    public synchronized void moveVaultPath(String oldPath, String newPath) throws IOException {
        List<RecentItem> records = readAllMutable();
        boolean changed = false;
        for (int i = 0; i < records.size(); i++) {
            RecentItem record = records.get(i);
            if (matchesPathOrDescendant(record.path(), oldPath)) {
                records.set(i, record.withPath(rebasedPath(record.path(), oldPath, newPath)));
                changed = true;
            }
        }
        if (changed) {
            writeAll(records);
        }
    }

    public synchronized void removeVaultPath(String vaultPath) throws IOException {
        List<RecentItem> records = readAllMutable();
        boolean changed = records.removeIf(record -> matchesPathOrDescendant(record.path(), vaultPath));
        if (changed) {
            writeAll(records);
        }
    }

    private void record(String vaultPath) throws IOException {
        if (vaultPath == null || vaultPath.isBlank() || "/".equals(vaultPath)) {
            return;
        }

        FileItem item = storageService.describeVaultPath(vaultPath);
        if (item.path().isBlank() || (item.directory() && !recentProperties.isRecordDirectories())) {
            return;
        }

        RecentItem record = new RecentItem(
                item.path(),
                item.directory() ? RecentTargetType.DIRECTORY : RecentTargetType.FILE,
                Instant.now()
        );
        List<RecentItem> records = readAllMutable();
        records.removeIf(existing -> existing.path().equals(record.path()));
        records.add(0, record);
        trim(records);
        writeAll(records);
    }

    private void trim(List<RecentItem> records) {
        int maxItems = Math.max(1, recentProperties.getMaxItems());
        while (records.size() > maxItems) {
            records.remove(records.size() - 1);
        }
    }

    private List<RecentItem> readAllMutable() throws IOException {
        if (!Files.exists(registryFile) || Files.size(registryFile) == 0L) {
            return new ArrayList<>();
        }
        return new ArrayList<>(objectMapper.readValue(registryFile.toFile(), RECENT_LIST));
    }

    private void writeAll(List<RecentItem> records) throws IOException {
        Files.createDirectories(registryFile.getParent());
        Path tempFile = registryFile.resolveSibling(registryFile.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), records);
        try {
            Files.move(tempFile, registryFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tempFile, registryFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Comparator<RecentListItem> comparator(RecentSort sort) {
        Comparator<RecentListItem> nameComparator = Comparator.comparing(
                item -> item.name().toLowerCase(Locale.ROOT)
        );
        return switch (sort) {
            case NAME -> nameComparator;
            case SIZE -> Comparator.comparingLong(RecentListItem::size).thenComparing(nameComparator);
            case MODIFIED -> Comparator.comparing(RecentListItem::modifiedAt).thenComparing(nameComparator);
            case TYPE -> Comparator.comparing((RecentListItem item) -> item.typeLabel().toLowerCase(Locale.ROOT))
                    .thenComparing(item -> item.mediaType().toLowerCase(Locale.ROOT))
                    .thenComparing(nameComparator);
            case RECENT -> Comparator.comparing(RecentListItem::lastAccessedAt).thenComparing(nameComparator);
        };
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }

    private boolean matches(RecentListItem item, String query) {
        if (query.isEmpty()) {
            return true;
        }
        return item.name().toLowerCase(Locale.ROOT).contains(query)
                || item.path().toLowerCase(Locale.ROOT).contains(query);
    }

    private boolean matchesPathOrDescendant(String candidatePath, String basePath) {
        return candidatePath.equals(basePath) || candidatePath.startsWith(basePath + "/");
    }

    private String rebasedPath(String candidatePath, String oldPath, String newPath) {
        if (candidatePath.equals(oldPath)) {
            return newPath;
        }
        return newPath + candidatePath.substring(oldPath.length());
    }
}
