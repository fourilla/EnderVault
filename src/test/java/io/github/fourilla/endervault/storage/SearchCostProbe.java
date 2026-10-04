package io.github.fourilla.endervault.storage;

import io.github.fourilla.endervault.bookmark.BookmarkItem;
import io.github.fourilla.endervault.bookmark.BookmarkItemType;
import io.github.fourilla.endervault.bookmark.BookmarkSearchSchema;
import io.github.fourilla.endervault.bookmark.BookmarkTitleSource;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filerequest.FileRequest;
import io.github.fourilla.endervault.filerequest.FileRequestSearchSchema;
import io.github.fourilla.endervault.filerequest.UploaderNamePolicy;
import io.github.fourilla.endervault.pending.PendingDecisionSearchSchema;
import io.github.fourilla.endervault.pending.PendingDecisionStatus;
import io.github.fourilla.endervault.recent.RecentListItem;
import io.github.fourilla.endervault.recent.RecentSearchSchema;
import io.github.fourilla.endervault.share.ShareLink;
import io.github.fourilla.endervault.share.ShareLinkSearchSchema;
import io.github.fourilla.endervault.share.ShareTargetType;
import io.github.fourilla.endervault.stickynote.StickyNote;
import io.github.fourilla.endervault.stickynote.StickyNoteContext;
import io.github.fourilla.endervault.stickynote.StickyNoteSearchSchema;
import io.github.fourilla.endervault.stickynote.StickyNoteSurface;
import io.github.fourilla.endervault.stickynote.StickyNoteTargetType;
import io.github.fourilla.endervault.trash.TrashRecord;
import io.github.fourilla.endervault.trash.TrashSearchSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/** Manual warm-cache probe, deliberately not a timing assertion or an automatically run test. */
public final class SearchCostProbe {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static volatile Object sink;

    private SearchCostProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--calls")) {
            filesystem(true);
            return;
        }
        System.out.println("scope,candidates,query,compile_us,predicate_ms,matches");
        for (int count : new int[] {1_000, 10_000}) {
            memory("files", count, i -> new StorageSearchSchema.Candidate(
                    Path.of(name(i)), path(i), false), StorageSearchSchema::compile,
                    "report", "name:report path:group3", "name:report name:7", "name:missing || extension:pdf");
            memory("recent", count, i -> new RecentListItem(file(i), NOW), RecentSearchSchema::compile,
                    "report", "path:group3 size:>=512B", "extension:pdf modified:>=2026-01-01", "name:missing || size:0");
            memory("bookmarks", count, i -> new BookmarkItem("id" + i, BookmarkItemType.LINK, null,
                    name(i), "https://example.invalid/" + path(i), "Original report", BookmarkTitleSource.MANUAL,
                    null, null, null, null, NOW, NOW, NOW), BookmarkSearchSchema::compile,
                    "report", "name:report name:7", "created:>=2026-01-01 type:link", "name:missing || note:report");
            memory("sticky-notes", count, i -> {
                StickyNote note = new StickyNote("id" + i, new StickyNoteContext(
                        StickyNoteTargetType.STORAGE, path(i), StickyNoteSurface.DETAIL), name(i),
                        0, 0, 0.0, 240, 200, false, 0, 1L, NOW, NOW);
                return new StickyNoteSearchSchema.Candidate(note, note.summary(), path(i), () -> true);
            }, StickyNoteSearchSchema::compile,
                    "report", "name:report target:group3", "status:available updated:>=2026-01-01", "name:missing || content:report");
            memory("pending", count, i -> new PendingDecisionSearchSchema.Candidate(name(i), "/" + path(i),
                    "ADMIN_UPLOAD", false, NOW, "Uploader", () -> PendingDecisionStatus.AWAITING_DECISION, (long) i),
                    PendingDecisionSearchSchema::compile,
                    "report", "destination:group3 size:>=512B", "status:awaiting_decision created:>=2026-01-01",
                    "name:missing || source:admin_upload");
            memory("shares", count, i -> new ShareLink("token" + i, path(i), ShareTargetType.FILE,
                    NOW, NOW.plusSeconds(3_600), true, true), query -> ShareLinkSearchSchema.compile(query, NOW),
                    "report", "name:report path:group3", "status:active preview:on", "name:missing || created:>=2026-01-01");
            memory("file-requests", count, i -> new FileRequest("id" + i, "token" + i, name(i),
                    "Original report", path(i), UploaderNamePolicy.OPTIONAL, 10_000, 100_000, 1_000,
                    List.of(), 0L, 0, List.of(), NOW, NOW.plusSeconds(3_600), true),
                    query -> FileRequestSearchSchema.compile(query, NOW),
                    "report", "name:report destination:group3", "status:active created:>=2026-01-01",
                    "name:missing || description:report");
            memory("trash", count, i -> new TrashRecord("id" + i, path(i), "group" + i % 32, name(i),
                    "trash" + i, false, i, "1 KiB", "File", NOW, NOW.plusSeconds(3_600)), TrashSearchSchema::compile,
                    "report", "name:report path:group3", "extension:pdf deleted:>=2026-01-01", "name:missing || type:file");
        }
        filesystem(false);
    }

    private static <T> void memory(String scope, int count, IntFunction<T> factory,
            Function<String, Predicate<T>> compile, String... queries) {
        List<T> candidates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) candidates.add(factory.apply(i));
        for (String query : queries) {
            double compileUs = median(() -> {
                for (int i = 0; i < 100; i++) sink = compile.apply(query);
                return sink;
            }) / 100_000.0;
            Predicate<T> predicate = compile.apply(query);
            int matches = matches(candidates, predicate);
            double predicateMs = median(() -> matches(candidates, predicate)) / 1_000_000.0;
            System.out.printf(Locale.ROOT, "%s,%d,%s,%.3f,%.3f,%d%n",
                    scope, count, query, compileUs, predicateMs, matches);
        }
    }

    private static <T> int matches(List<T> candidates, Predicate<T> predicate) {
        int matches = 0;
        for (T candidate : candidates) if (predicate.test(candidate)) matches++;
        return matches;
    }

    private static double median(Supplier<?> action) {
        for (int i = 0; i < 5; i++) sink = action.get();
        long[] elapsed = new long[9];
        for (int i = 0; i < elapsed.length; i++) {
            long start = System.nanoTime();
            sink = action.get();
            elapsed[i] = System.nanoTime() - start;
        }
        Arrays.sort(elapsed);
        return elapsed[elapsed.length / 2];
    }

    private static void filesystem(boolean countCalls) throws Exception {
        Path root = Files.createTempDirectory("endervault-search-probe-");
        try {
            NasProperties properties = new NasProperties();
            properties.getStorage().setRoot(root);
            StorageService storage = new StorageService(properties);
            storage.initialize();
            Path fixture = Files.createDirectory(root.resolve("fixture"));
            for (int i = 0; i < 1_024; i++) {
                Path directory = Files.createDirectories(fixture.resolve("group" + i % 32));
                Path file = Files.write(directory.resolve(name(i)), new byte[i % 2 == 0 ? 512 : 1_024]);
                Files.setLastModifiedTime(file, FileTime.from(NOW));
            }
            // A shuffled, wide directory exposes filesystem calls made by sorting comparisons.
            Path wide = Files.createDirectory(root.resolve("wide"));
            for (int i = 0; i < 1_024; i++) {
                int shuffled = i * 719 % 1_024;
                Files.write(wide.resolve(name(shuffled)), new byte[512]);
            }
            System.out.println(countCalls ? "filesystem_root,query,matches,api_calls"
                    : "filesystem_root,query,total_ms,matches,result_hash");
            for (String location : List.of("fixture", "wide")) {
                for (String query : List.of("name:missing", "report", "name:report7", "size:>=512B",
                        "modified:>=2026-01-01", "name:report7 size:>=512B", "size:>=512B name:report7")) {
                    if (countCalls && !List.of("name:missing", "report", "size:>=512B").contains(query)) continue;
                    Supplier<List<FileItem>> action = () -> {
                        try { return storage.search(StorageScope.VAULT, location, query, true); }
                        catch (Exception ex) { throw new IllegalStateException(ex); }
                    };
                    if (countCalls) {
                        Map<String, Integer> calls = new HashMap<>();
                        Path searchRoot = root.resolve(location);
                        // Instrumentation is only used for counts, never for elapsed timings.
                        try (MockedStatic<Files> ignored = Mockito.mockStatic(Files.class, invocation -> {
                            if (invocation.getArguments().length > 0
                                    && invocation.getArgument(0) instanceof Path path
                                    && path.startsWith(searchRoot) && !path.equals(searchRoot)) {
                                calls.merge(invocation.getMethod().getName(), 1, Integer::sum);
                            }
                            return invocation.callRealMethod();
                        })) {
                            System.out.printf("%s,%s,%d,%s%n", location, query, action.get().size(), calls);
                        }
                        continue;
                    }
                    double elapsedMs = median(action) / 1_000_000.0;
                    List<FileItem> results = action.get();
                    int hash = results.stream().map(item -> item.path() + ":" + item.size()).toList().hashCode();
                    System.out.printf(Locale.ROOT, "%s,%s,%.3f,%d,%d%n", location, query, elapsedMs, results.size(), hash);
                }
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static String name(int i) { return "Report" + i + (i % 2 == 0 ? ".PDF" : ".txt"); }
    private static String path(int i) { return "group" + i % 32 + "/" + name(i); }
    private static FileItem file(int i) {
        return new FileItem(name(i), path(i), false, i, "1 KiB", "2026-10-04 09:00", NOW,
                "application/pdf", true, false, false);
    }
}
