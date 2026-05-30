package io.github.fourilla.endervault.favorite;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageService;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class FavoriteService {

    private static final TypeReference<List<FavoriteItem>> FAVORITE_LIST = new TypeReference<>() {
    };

    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final Path registryFile;

    public FavoriteService(StorageService storageService, ObjectMapper objectMapper, NasProperties nasProperties) {
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.registryFile = nasProperties.getStorage().getRoot()
                .toAbsolutePath()
                .normalize()
                .resolve(nasProperties.getStorage().getMetadataDirectory())
                .resolve("favorites.json");
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        Files.createDirectories(registryFile.getParent());
        if (!Files.exists(registryFile)) {
            writeAll(List.of());
        }
    }

    public synchronized List<FavoriteItem> list() throws IOException {
        return List.copyOf(readAllMutable());
    }

    public synchronized List<FavoriteItem> listExisting() throws IOException {
        List<FavoriteItem> favorites = readAllMutable();
        return favorites.stream()
                .filter(this::exists)
                .toList();
    }

    public synchronized Set<String> favoritePaths() throws IOException {
        Set<String> paths = new LinkedHashSet<>();
        for (FavoriteItem favorite : readAllMutable()) {
            paths.add(favorite.path());
        }
        return Set.copyOf(paths);
    }

    public synchronized boolean isFavorite(String vaultPath) throws IOException {
        return readAllMutable().stream().anyMatch(favorite -> favorite.path().equals(vaultPath));
    }

    public synchronized FavoriteItem toggle(String vaultPath) throws IOException {
        if (vaultPath == null || vaultPath.isBlank() || "/".equals(vaultPath)) {
            throw new StorageAccessException("Path is required.");
        }

        FileItem item = storageService.describeVaultPath(vaultPath);
        List<FavoriteItem> favorites = readAllMutable();
        for (int i = 0; i < favorites.size(); i++) {
            if (favorites.get(i).path().equals(item.path())) {
                favorites.remove(i);
                writeAll(favorites);
                return null;
            }
        }

        FavoriteItem favorite = new FavoriteItem(
                item.path(),
                item.directory() ? FavoriteTargetType.DIRECTORY : FavoriteTargetType.FILE,
                Instant.now()
        );
        favorites.add(favorite);
        writeAll(favorites);
        return favorite;
    }

    public synchronized void remove(String vaultPath) throws IOException {
        List<FavoriteItem> favorites = readAllMutable();
        boolean changed = favorites.removeIf(favorite -> favorite.path().equals(vaultPath));
        if (changed) {
            writeAll(favorites);
        }
    }

    public synchronized void move(String vaultPath, String direction) throws IOException {
        if (!"up".equals(direction) && !"down".equals(direction)) {
            return;
        }

        List<FavoriteItem> favorites = readAllMutable();
        int index = indexOf(favorites, vaultPath);
        if (index < 0) {
            return;
        }

        int targetIndex = "up".equals(direction) ? index - 1 : index + 1;
        if (targetIndex < 0 || targetIndex >= favorites.size()) {
            return;
        }

        FavoriteItem favorite = favorites.get(index);
        favorites.set(index, favorites.get(targetIndex));
        favorites.set(targetIndex, favorite);
        writeAll(favorites);
    }

    public synchronized void moveVaultPath(String oldPath, String newPath) throws IOException {
        List<FavoriteItem> favorites = readAllMutable();
        boolean changed = false;
        for (int i = 0; i < favorites.size(); i++) {
            FavoriteItem favorite = favorites.get(i);
            if (matchesPathOrDescendant(favorite.path(), oldPath)) {
                favorites.set(i, favorite.withPath(rebasedPath(favorite.path(), oldPath, newPath)));
                changed = true;
            }
        }
        if (changed) {
            writeAll(favorites);
        }
    }

    public synchronized void removeVaultPath(String vaultPath) throws IOException {
        List<FavoriteItem> favorites = readAllMutable();
        boolean changed = favorites.removeIf(favorite -> matchesPathOrDescendant(favorite.path(), vaultPath));
        if (changed) {
            writeAll(favorites);
        }
    }

    private boolean exists(FavoriteItem favorite) {
        try {
            storageService.describeVaultPath(favorite.path());
            return true;
        } catch (IOException | StorageAccessException ex) {
            return false;
        }
    }

    private int indexOf(List<FavoriteItem> favorites, String vaultPath) {
        for (int i = 0; i < favorites.size(); i++) {
            if (favorites.get(i).path().equals(vaultPath)) {
                return i;
            }
        }
        return -1;
    }

    private List<FavoriteItem> readAllMutable() throws IOException {
        if (!Files.exists(registryFile) || Files.size(registryFile) == 0L) {
            return new ArrayList<>();
        }
        return new ArrayList<>(objectMapper.readValue(registryFile.toFile(), FAVORITE_LIST));
    }

    private void writeAll(List<FavoriteItem> favorites) throws IOException {
        Files.createDirectories(registryFile.getParent());
        Path tempFile = registryFile.resolveSibling(registryFile.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), favorites);
        try {
            Files.move(tempFile, registryFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tempFile, registryFile, StandardCopyOption.REPLACE_EXISTING);
        }
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
