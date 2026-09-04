package io.github.fourilla.endervault.stickynote;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.bookmark.BookmarkItem;
import io.github.fourilla.endervault.bookmark.BookmarkService;
import io.github.fourilla.endervault.common.JsonRegistry;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filerequest.FileRequest;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageService;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class StickyNoteService {

    private static final TypeReference<List<StickyNote>> NOTE_LIST = new TypeReference<>() {
    };
    private static final int MAX_CONTENT_LENGTH = 10_000;
    private static final int MAX_NOTES_PER_CONTEXT = 20;
    private static final int MAX_TOTAL_NOTES = 1_000;

    private final JsonRegistry<List<StickyNote>> registry;
    private final StorageService storageService;
    private final BookmarkService bookmarkService;
    private final FileRequestService fileRequestService;
    private final StickyNotePageCatalog pageCatalog;

    public StickyNoteService(
            ObjectMapper objectMapper,
            NasProperties nasProperties,
            StorageService storageService,
            BookmarkService bookmarkService,
            FileRequestService fileRequestService,
            StickyNotePageCatalog pageCatalog
    ) {
        this.storageService = storageService;
        this.bookmarkService = bookmarkService;
        this.fileRequestService = fileRequestService;
        this.pageCatalog = pageCatalog;
        this.registry = new JsonRegistry<>(
                objectMapper,
                nasProperties.getStorage().getRoot()
                        .toAbsolutePath()
                        .normalize()
                        .resolve(nasProperties.getStorage().getMetadataDirectory())
                        .resolve("sticky-notes.json"),
                NOTE_LIST,
                List::of,
                JsonRegistry.CorruptionPolicy.BACKUP_AND_RESET
        );
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        registry.initialize();
    }

    public synchronized List<StickyNote> list(StickyNoteContext context) throws IOException {
        StickyNoteContext normalized = normalizeContext(context, true);
        return registry.read().stream()
                .filter(note -> normalized.equals(note.context()))
                .sorted(Comparator.comparingInt(StickyNote::layer).thenComparing(StickyNote::createdAt))
                .toList();
    }

    public synchronized List<StickyNote> listAll() throws IOException {
        return registry.read().stream()
                .sorted(Comparator.comparing(StickyNote::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public synchronized StickyNote create(StickyNoteContext context, int x, int y) throws IOException {
        StickyNoteContext normalized = normalizeContext(context, true);
        List<StickyNote> notes = readAllMutable();
        if (notes.size() >= MAX_TOTAL_NOTES) {
            throw new StorageAccessException("The sticky note limit has been reached.");
        }
        long contextCount = notes.stream().filter(note -> normalized.equals(note.context())).count();
        if (contextCount >= MAX_NOTES_PER_CONTEXT) {
            throw new StorageAccessException("This page already has the maximum number of sticky notes.");
        }

        Instant now = Instant.now();
        int nextLayer = notes.stream().mapToInt(StickyNote::layer).max().orElse(0) + 1;
        StickyNote note = new StickyNote(
                UUID.randomUUID().toString(),
                normalized,
                "",
                clamp(x, 0, 10_000),
                clamp(y, 0, 10_000),
                280,
                220,
                false,
                nextLayer,
                0L,
                now,
                now
        );
        notes.add(note);
        writeAll(notes);
        return note;
    }

    public synchronized StickyNote update(String id, StickyNoteSnapshot snapshot) throws IOException {
        if (snapshot == null) {
            throw new StorageAccessException("Sticky note data is required.");
        }
        List<StickyNote> notes = readAllMutable();
        int index = indexOf(notes, id);
        StickyNote current = notes.get(index);
        StickyNoteSnapshot normalized = new StickyNoteSnapshot(
                normalizeContent(snapshot.content()),
                clamp(snapshot.x(), 0, 10_000),
                clamp(snapshot.y(), 0, 10_000),
                clamp(snapshot.width(), 220, 600),
                clamp(snapshot.height(), 140, 700),
                snapshot.collapsed(),
                clamp(snapshot.layer(), 1, 1_000_000)
        );
        StickyNote updated = current.withSnapshot(normalized, Instant.now());
        notes.set(index, updated);
        writeAll(notes);
        return updated;
    }

    public synchronized StickyNote delete(String id) throws IOException {
        List<StickyNote> notes = readAllMutable();
        StickyNote removed = notes.remove(indexOf(notes, id));
        writeAll(notes);
        return removed;
    }

    public synchronized StickyNote find(String id) throws IOException {
        if (id == null || id.isBlank()) {
            return null;
        }
        return registry.read().stream().filter(note -> id.equals(note.id())).findFirst().orElse(null);
    }

    public synchronized void moveVaultPath(String oldPath, String newPath) throws IOException {
        String normalizedOldPath = normalizeVaultKey(oldPath);
        String normalizedNewPath = normalizeVaultKey(newPath);
        List<StickyNote> notes = readAllMutable();
        boolean changed = false;
        Instant now = Instant.now();
        for (int i = 0; i < notes.size(); i++) {
            StickyNote note = notes.get(i);
            StickyNoteContext context = note.context();
            if (context == null || context.targetType() != StickyNoteTargetType.STORAGE) {
                continue;
            }
            String targetKey = normalizeVaultKey(context.targetKey());
            if (matchesPathOrDescendant(targetKey, normalizedOldPath)) {
                String rebased = targetKey.equals(normalizedOldPath)
                        ? normalizedNewPath
                        : normalizedNewPath + targetKey.substring(normalizedOldPath.length());
                notes.set(i, note.withContext(
                        new StickyNoteContext(StickyNoteTargetType.STORAGE, rebased, context.surface()),
                        now
                ));
                changed = true;
            }
        }
        if (changed) {
            writeAll(notes);
        }
    }

    public boolean targetExists(StickyNoteContext context) {
        try {
            normalizeContext(context, true);
            return true;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    public String contextLabel(StickyNoteContext context) {
        if (context == null || context.targetType() == null) {
            return "Unknown target";
        }
        try {
            return switch (context.targetType()) {
                case PAGE -> {
                    StickyNotePageCatalog.PageDefinition page = pageCatalog.byKey(context.normalizedTargetKey());
                    yield page == null ? context.normalizedTargetKey() : page.label();
                }
                case STORAGE -> {
                    String key = normalizeVaultKey(context.targetKey());
                    if (key.isBlank()) {
                        yield "Files /";
                    }
                    FileItem item = storageService.describeVaultPath(key);
                    yield item.name() + " (" + key + ")";
                }
                case BOOKMARK -> {
                    BookmarkItem bookmark = bookmarkService.find(context.normalizedTargetKey());
                    yield bookmark == null ? context.normalizedTargetKey() : bookmark.title();
                }
                case FILE_REQUEST -> {
                    FileRequest fileRequest = fileRequestService.require(context.normalizedTargetKey());
                    yield fileRequest.title();
                }
            };
        } catch (Exception ex) {
            return context.normalizedTargetKey().isBlank() ? "Unknown target" : context.normalizedTargetKey();
        }
    }

    public String openUrl(StickyNoteContext context) {
        if (context == null || context.targetType() == null) {
            return null;
        }
        return switch (context.targetType()) {
            case PAGE -> {
                StickyNotePageCatalog.PageDefinition page = pageCatalog.byKey(context.normalizedTargetKey());
                yield page == null ? null : page.path();
            }
            case STORAGE -> storageOpenUrl(context);
            case BOOKMARK -> bookmarkOpenUrl(context);
            case FILE_REQUEST -> fileRequestOpenUrl(context);
        };
    }

    private StickyNoteContext normalizeContext(StickyNoteContext context, boolean requireExisting) throws IOException {
        if (context == null || context.targetType() == null || context.surface() == null) {
            throw new StorageAccessException("A valid sticky note context is required.");
        }
        String targetKey = context.normalizedTargetKey();
        switch (context.targetType()) {
            case PAGE -> {
                if (pageCatalog.byKey(targetKey) == null) {
                    throw new StorageAccessException("The sticky note page is not supported.");
                }
            }
            case STORAGE -> {
                targetKey = normalizeVaultKey(targetKey);
                if (requireExisting) {
                    if (targetKey.isBlank() && context.surface() == StickyNoteSurface.BROWSER) {
                        storageService.ensureVaultDirectory("");
                    } else if (targetKey.isBlank() && context.surface() == StickyNoteSurface.SEARCH) {
                        storageService.ensureVaultDirectory("");
                    } else {
                        storageService.describeVaultPath(targetKey);
                    }
                }
            }
            case BOOKMARK -> {
                if (targetKey.isBlank() || (requireExisting && bookmarkService.find(targetKey) == null)) {
                    throw new StorageAccessException("The bookmark target was not found.");
                }
            }
            case FILE_REQUEST -> {
                if (targetKey.isBlank()) {
                    throw new StorageAccessException("The file request target was not found.");
                }
                if (requireExisting) {
                    fileRequestService.require(targetKey);
                }
            }
        }
        return new StickyNoteContext(context.targetType(), targetKey, context.surface());
    }

    private String storageOpenUrl(StickyNoteContext context) {
        String key = normalizeVaultKey(context.targetKey());
        String encoded = urlEncode(key);
        return switch (context.surface()) {
            case DETAIL -> "/files/detail?path=" + encoded;
            case SEARCH -> key.isBlank() ? "/files" : "/files?path=" + encoded;
            default -> key.isBlank() ? "/files" : "/files?path=" + encoded;
        };
    }

    private String bookmarkOpenUrl(StickyNoteContext context) {
        try {
            BookmarkItem bookmark = bookmarkService.find(context.normalizedTargetKey());
            if (bookmark == null) {
                return null;
            }
            return context.surface() == StickyNoteSurface.DETAIL
                    ? "/files/bookmarks/detail?id=" + urlEncode(bookmark.id())
                    : "/files/bookmarks?directory=" + urlEncode(bookmark.directory() ? bookmark.id() : bookmark.parentId());
        } catch (IOException ex) {
            return null;
        }
    }

    private String fileRequestOpenUrl(StickyNoteContext context) {
        try {
            FileRequest fileRequest = fileRequestService.require(context.normalizedTargetKey());
            return "/admin/file-requests/" + urlEncode(fileRequest.id());
        } catch (IOException ex) {
            return null;
        }
    }

    private String normalizeContent(String content) {
        String normalized = content == null ? "" : content;
        if (normalized.length() > MAX_CONTENT_LENGTH) {
            throw new StorageAccessException("Sticky notes can contain up to " + MAX_CONTENT_LENGTH + " characters.");
        }
        return normalized;
    }

    private int indexOf(List<StickyNote> notes, String id) {
        for (int i = 0; i < notes.size(); i++) {
            if (notes.get(i).id().equals(id)) {
                return i;
            }
        }
        throw new StorageAccessException("Sticky note was not found.");
    }

    private List<StickyNote> readAllMutable() throws IOException {
        return new ArrayList<>(registry.read());
    }

    private void writeAll(List<StickyNote> notes) throws IOException {
        registry.write(List.copyOf(notes));
    }

    private String normalizeVaultKey(String value) {
        String key = value == null ? "" : value.trim().replace('\\', '/');
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        while (key.endsWith("/") && !key.isBlank()) {
            key = key.substring(0, key.length() - 1);
        }
        return key;
    }

    private boolean matchesPathOrDescendant(String candidate, String base) {
        return candidate.equals(base) || (!base.isBlank() && candidate.startsWith(base + "/"));
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
