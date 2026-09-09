package io.github.fourilla.endervault.directorymerge;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.common.NaturalNameComparator;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.storage.StorageService;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

import static io.github.fourilla.endervault.directorymerge.DirectoryMergePlan.*;

@Service
public class DirectoryMergePlanner {
    static final int MAX_ENTRIES = 100_000;
    static final int MAX_DEPTH = 128;
    private final StorageService storage;
    private final Path vault;

    public DirectoryMergePlanner(StorageService storage, NasProperties properties) {
        this.storage = storage;
        vault = properties.getStorage().getRoot().toAbsolutePath().normalize();
    }

    public DirectoryMergePlan planTransfer(Operation operation, String sourcePath,
            String destinationDirectory, StorageProgressListener listener) throws IOException {
        if (operation != Operation.COPY && operation != Operation.MOVE) {
            throw new StorageAccessException("A transfer must be copy or move.");
        }
        Path source = storage.resolveVaultDirectory(sourcePath).toAbsolutePath().normalize();
        if (source.equals(vault)) throw new StorageAccessException("The storage root cannot be transferred.");
        String destination = storage.normalizeVaultDirectory(destinationDirectory);
        String name = source.getFileName().toString();
        storage.validateVaultEntryName(name);
        String targetPath = join(destination, name);
        Path target = storage.resolveVaultCommitTarget(targetPath);
        return scan(operation, relative(vault, source), source, targetPath, target, listener);
    }

    /** The caller must obtain this decision from the server repository, never from request JSON. */
    public DirectoryMergePlan planPending(PendingFileDecision decision, StorageProgressListener listener)
            throws IOException {
        return planPending(decision, listener, Map.of());
    }

    DirectoryMergePlan planPending(PendingFileDecision decision, StorageProgressListener listener,
            Map<String, String> targetNames) throws IOException {
        if (!decision.directory()) throw new StorageAccessException("Pending item is not a directory.");
        storage.validateVaultEntryName(decision.originalFilename());
        Path source = storage.resolveFileStagingFile(decision.stagingFilename());
        String destination = storage.normalizeVaultDirectory(decision.destinationPath());
        String targetPath = join(destination, decision.originalFilename());
        return scan(Operation.PENDING, decision.id(), source, targetPath,
                storage.resolveVaultCommitTarget(targetPath), listener, targetNames);
    }

    private DirectoryMergePlan scan(Operation operation, String reference, Path source, String targetPath,
            Path target, StorageProgressListener listener) throws IOException {
        return scan(operation, reference, source, targetPath, target, listener, Map.of());
    }

    private DirectoryMergePlan scan(Operation operation, String reference, Path source, String targetPath,
            Path target, StorageProgressListener listener, Map<String, String> targetNames) throws IOException {
        StorageProgressListener progress = listener == null ? StorageProgressListener.NOOP : listener;
        rejectLinks(source);
        rejectLinks(target);
        if (source.startsWith(target) || target.startsWith(source)) {
            throw new StorageAccessException("Source and destination directories must not overlap.");
        }
        if (snapshot(source).kind() != Kind.DIRECTORY) throw new StorageAccessException("Source is not a directory.");
        List<Item> items = new ArrayList<>();
        Map<String, String> blockedDirectories = new HashMap<>();
        Map<String, Path> destinations = new HashMap<>();
        java.util.Set<Path> usedDestinations = new java.util.HashSet<>();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                add(path);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                add(path);
                return FileVisitResult.CONTINUE;
            }
            private void add(Path path) throws IOException {
                progress.checkCanceled();
                if (items.size() >= MAX_ENTRIES) throw new StorageAccessException("Directory merge exceeds 100,000 entries.");
                String relative = relative(source, path);
                if (!relative.isEmpty() && source.relativize(path).getNameCount() > MAX_DEPTH) {
                    throw new StorageAccessException("Directory merge exceeds 128 path levels.");
                }
                if (!relative.isEmpty()) storage.validateVaultEntryName(path.getFileName().toString());
                rejectLinks(path);
                Snapshot from = snapshot(path);
                String parent = relative.isEmpty() ? null : relative(source, path.getParent());
                String blockedBy = parent == null ? null : blockedDirectories.get(parent);
                String targetName = targetNames.getOrDefault(relative, path.getFileName().toString());
                storage.validateVaultEntryName(targetName);
                Path destination = parent == null ? targetNames.containsKey("") ? target.resolveSibling(targetName) : target
                        : destinations.get(parent).resolve(targetName);
                if (!usedDestinations.add(destination)) throw new StorageAccessException("Pending target names overlap; manual review is required.");
                destinations.put(relative, destination);
                Snapshot to = null;
                Conflict conflict;
                if (blockedBy != null) {
                    conflict = Conflict.BLOCKED_BY_PARENT;
                } else {
                    rejectLinks(destination);
                    to = snapshotIfPresent(destination);
                    conflict = to == null ? Conflict.ADD : from.kind() != to.kind() ? Conflict.TYPE_CONFLICT
                            : from.kind() == Kind.DIRECTORY ? Conflict.MERGE : Conflict.FILE_CONFLICT;
                }
                String id = UUID.randomUUID().toString();
                if (from.kind() == Kind.DIRECTORY) {
                    if (blockedBy != null) blockedDirectories.put(relative, blockedBy);
                    else if (conflict == Conflict.TYPE_CONFLICT) blockedDirectories.put(relative, id);
                }
                items.add(new Item(id, relative, from, to, conflict, blockedBy));
                progress.onItemProcessed();
            }
        });
        // Catch observable changes during enumeration; the executor must still revalidate before every write.
        for (Item item : items) {
            progress.checkCanceled();
            Path path = source.resolve(item.relativePath());
            rejectLinks(path);
            if (!item.source().equals(snapshot(path))) throw new StorageAccessException("Source changed during directory scan. Scan again.");
        }
        items.sort(Comparator.comparing(Item::relativePath, NaturalNameComparator.INSTANCE));
        var retainedNames = new HashMap<String, String>();
        for (var item : items) if (targetNames.containsKey(item.relativePath())) retainedNames.put(item.relativePath(), targetNames.get(item.relativePath()));
        return new DirectoryMergePlan(UUID.randomUUID().toString(), operation, reference, targetPath, Instant.now(), items, retainedNames);
    }

    static Snapshot snapshot(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isSymbolicLink() || (!attrs.isDirectory() && !attrs.isRegularFile())) {
            throw new StorageAccessException("Directory merge does not support links or special files.");
        }
        return new Snapshot(attrs.isDirectory() ? Kind.DIRECTORY : Kind.FILE,
                attrs.isDirectory() ? 0 : attrs.size(), attrs.lastModifiedTime().toInstant(),
                attrs.fileKey() == null ? null : attrs.fileKey().toString(), attrs.creationTime().toInstant());
    }

    static Snapshot snapshotIfPresent(Path path) throws IOException {
        try { return snapshot(path); }
        catch (NoSuchFileException ex) { return null; }
    }

    static void rejectLinks(Path path) throws IOException {
        for (Path current = path.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new StorageAccessException("Directory merge cannot traverse symbolic links.");
        }
    }

    private static String join(String parent, String child) { return parent.isEmpty() ? child : parent + "/" + child; }
    private static String relative(Path root, Path path) { return root.relativize(path).toString().replace('\\', '/'); }
}
