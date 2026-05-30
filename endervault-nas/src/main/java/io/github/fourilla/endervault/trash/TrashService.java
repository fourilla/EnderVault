package io.github.fourilla.endervault.trash;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageService;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TrashService {

    private final StorageService storageService;
    private final TrashRepository trashRepository;
    private final ShareLinkService shareLinkService;
    private final NasProperties.Trash trashProperties;

    public TrashService(
            StorageService storageService,
            TrashRepository trashRepository,
            ShareLinkService shareLinkService,
            NasProperties nasProperties
    ) {
        this.storageService = storageService;
        this.trashRepository = trashRepository;
        this.shareLinkService = shareLinkService;
        this.trashProperties = nasProperties.getTrash();
    }

    @PostConstruct
    public void initialize() throws IOException {
        if (trashProperties.isCleanupOnStartup()) {
            cleanupExpired();
        }
    }

    public synchronized List<TrashRecord> list() throws IOException {
        cleanupExpired();
        return trashRepository.list();
    }

    public synchronized List<TrashRecord> moveToTrash(String directoryPath, List<String> itemNames) throws IOException {
        List<TrashRecord> records = new ArrayList<>();
        for (String itemName : itemNames) {
            FileItem item = storageService.describeVaultChild(directoryPath, itemName);
            records.add(moveItemToTrash(item));
        }
        return List.copyOf(records);
    }

    public synchronized TrashRecord moveVaultPathToTrash(String vaultPath) throws IOException {
        FileItem item = storageService.describeVaultPath(vaultPath);
        return moveItemToTrash(item);
    }

    public synchronized void restore(String id) throws IOException {
        TrashRecord record = requireRecord(id);
        storageService.restoreTrashItem(record.trashName(), record.originalPath());
        trashRepository.remove(record.id());
    }

    public synchronized void deletePermanently(String id) throws IOException {
        TrashRecord record = requireRecord(id);
        storageService.deleteTrashItemIfExists(record.trashName());
        trashRepository.remove(record.id());
    }

    public synchronized int empty() throws IOException {
        int count = trashRepository.list().size();
        storageService.deleteAllTrashItems();
        trashRepository.clear();
        return count;
    }

    public synchronized int cleanupExpired() throws IOException {
        Instant now = Instant.now();
        List<String> staleRecordIds = new ArrayList<>();
        for (TrashRecord record : trashRepository.list()) {
            if (!storageService.trashItemExists(record.trashName())) {
                staleRecordIds.add(record.id());
                continue;
            }
            if (record.expired(now)) {
                storageService.deleteTrashItemIfExists(record.trashName());
                staleRecordIds.add(record.id());
            }
        }
        trashRepository.removeAll(staleRecordIds);
        return staleRecordIds.size();
    }

    private TrashRecord moveItemToTrash(FileItem item) throws IOException {
        if (item.path() == null || item.path().isBlank()) {
            throw new StorageAccessException("Vault root cannot be moved to trash.");
        }

        String id = UUID.randomUUID().toString();
        Instant deletedAt = Instant.now();
        Instant expiresAt = deletedAt.plus(Duration.ofDays(trashProperties.getRetentionDays()));
        TrashRecord record = new TrashRecord(
                id,
                item.path(),
                parentPathOf(item.path()),
                item.name(),
                id,
                item.directory(),
                item.size(),
                item.sizeLabel(),
                item.typeLabel(),
                deletedAt,
                expiresAt
        );

        storageService.moveVaultPathToTrash(item.path(), record.trashName());
        try {
            trashRepository.add(record);
        } catch (IOException ex) {
            storageService.restoreTrashItem(record.trashName(), record.originalPath());
            throw ex;
        }
        shareLinkService.revokeVaultPath(item.path());
        return record;
    }

    private TrashRecord requireRecord(String id) throws IOException {
        if (id == null || id.isBlank()) {
            throw new StorageAccessException("Trash item id is required.");
        }
        return trashRepository.find(id)
                .orElseThrow(() -> new NoSuchFileException(id));
    }

    private String parentPathOf(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? "" : path.substring(0, index);
    }
}
