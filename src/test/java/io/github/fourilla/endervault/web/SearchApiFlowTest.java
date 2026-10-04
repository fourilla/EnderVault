package io.github.fourilla.endervault.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.fourilla.endervault.bookmark.BookmarkSearchSchema;
import io.github.fourilla.endervault.bookmark.BookmarkService;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.filerequest.FileRequestSearchSchema;
import io.github.fourilla.endervault.filerequest.UploaderNamePolicy;
import io.github.fourilla.endervault.pending.PendingDecisionSearchSchema;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.pending.PendingFileDecisionSource;
import io.github.fourilla.endervault.recent.RecentSearchSchema;
import io.github.fourilla.endervault.recent.RecentService;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.share.ShareLinkSearchSchema;
import io.github.fourilla.endervault.stickynote.StickyNoteContext;
import io.github.fourilla.endervault.stickynote.StickyNoteSearchSchema;
import io.github.fourilla.endervault.stickynote.StickyNoteService;
import io.github.fourilla.endervault.stickynote.StickyNoteSnapshot;
import io.github.fourilla.endervault.stickynote.StickyNoteSurface;
import io.github.fourilla.endervault.stickynote.StickyNoteTargetType;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.trash.TrashRecord;
import io.github.fourilla.endervault.trash.TrashRepository;
import io.github.fourilla.endervault.trash.TrashSearchSchema;
import io.github.fourilla.endervault.trash.TrashService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class SearchApiFlowTest {

    private static final Path ROOT = createTempRoot();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    BookmarkService bookmarks;

    @Autowired
    RecentService recent;

    @Autowired
    StickyNoteService notes;

    @Autowired
    StorageService storage;

    @Autowired
    PendingFileDecisionService pending;

    @Autowired
    ShareLinkService shares;

    @Autowired
    FileRequestService fileRequests;

    @Autowired
    TrashService trash;

    @Autowired
    TrashRepository trashRecords;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nas.storage.root", ROOT::toString);
        registry.add("nas.browser.default-page-size", () -> 1);
    }

    @Test
    void filesSchemaUsesTheRegisteredFieldsAndExposesNoServerPathsOrExtractors() throws Exception {
        var response = mockMvc.perform(get("/api/v1/search/schemas/files"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.scope").value("files"))
                .andExpect(jsonPath("$.defaultFields[0]").value("name"))
                .andExpect(jsonPath("$.defaultOperator").value("AND"))
                .andExpect(jsonPath("$.fields.length()").value(6))
                .andExpect(jsonPath("$.fields[0].key").value("name"))
                .andExpect(jsonPath("$.fields[0].type").value("TEXT"))
                .andExpect(jsonPath("$.fields[1].key").value("path"))
                .andExpect(jsonPath("$.fields[1].type").value("PATH"))
                .andExpect(jsonPath("$.fields[2].key").value("type"))
                .andExpect(jsonPath("$.fields[2].type").value("ENUM"))
                .andExpect(jsonPath("$.fields[2].values[0]").value("file"))
                .andExpect(jsonPath("$.fields[2].values[1]").value("directory"))
                .andExpect(jsonPath("$.fields[3].key").value("modified"))
                .andExpect(jsonPath("$.fields[3].type").value("DATE_TIME"))
                .andExpect(jsonPath("$.fields[3].timeZone").isNotEmpty())
                .andExpect(jsonPath("$.fields[4].key").value("size"))
                .andExpect(jsonPath("$.fields[4].type").value("NUMBER"))
                .andExpect(jsonPath("$.fields[4].units[0]").value("B"))
                .andExpect(jsonPath("$.fields[4].units[6]").value("MiB"))
                .andExpect(jsonPath("$.fields[5].key").value("extension"))
                .andExpect(jsonPath("$.fields[5].operators[0]").value("EQUALS"))
                .andExpect(jsonPath("$.limits.maxLength").value(4096))
                .andExpect(jsonPath("$.limits.maxTokens").value(256))
                .andExpect(jsonPath("$.limits.maxTerms").value(128))
                .andExpect(jsonPath("$.limits.maxDepth").value(16))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(ROOT.toString(), "compiler", "extractor", "Candidate");
    }

    @Test
    void plainAndNameFieldQueriesReturnTheSameFileResults() throws Exception {
        Path directory = directory();
        Files.writeString(directory.resolve("report.txt"), "yes");
        Files.writeString(directory.resolve("other.txt"), "no");
        var plain = mockMvc.perform(get("/api/v1/fs/search")
                        .param("path", directory.getFileName().toString()).param("q", "REPORT"))
                .andExpect(status().isOk()).andReturn();
        var tagged = mockMvc.perform(get("/api/v1/fs/search")
                        .param("path", directory.getFileName().toString()).param("q", "name:report"))
                .andExpect(status().isOk()).andReturn();

        assertThat(objectMapper.readTree(plain.getResponse().getContentAsByteArray()).get("entries"))
                .isEqualTo(objectMapper.readTree(tagged.getResponse().getContentAsByteArray()).get("entries"));
    }

    @Test
    void quotedPhraseIsContinuousPartialMatchingNotTheAndOfSeparateWords() throws Exception {
        Path directory = directory();
        Files.writeString(directory.resolve("summer holiday report.txt"), "continuous");
        Files.writeString(directory.resolve("holiday summer.txt"), "reversed");
        Files.writeString(directory.resolve("summer.txt"), "one term");
        String path = directory.getFileName().toString();

        mockMvc.perform(get("/api/v1/fs/search").param("path", path).param("q", "summer holiday"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalItems").value(2));
        for (String query : new String[] {"\"summer holiday\"", "name:\"summer holiday\""}) {
            mockMvc.perform(get("/api/v1/fs/search").param("path", path).param("q", query))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.search.query").value(query))
                    .andExpect(jsonPath("$.page.totalItems").value(1))
                    .andExpect(jsonPath("$.entries[0].name").value("summer holiday report.txt"));
        }
    }

    @Test
    void typedAndOrConditionsFilterBeforeNaturalSortAndPagination() throws Exception {
        Path directory = directory();
        Files.createDirectories(directory.resolve("nested"));
        Files.writeString(directory.resolve("Q11.txt"), "eleven");
        Files.writeString(directory.resolve("Q3.txt"), "three");
        Files.writeString(directory.resolve("Q2.txt"), "excluded");
        Files.setLastModifiedTime(directory.resolve("Q11.txt"),
                FileTime.from(Instant.parse("2024-01-02T00:00:00Z")));
        Files.setLastModifiedTime(directory.resolve("Q3.txt"),
                FileTime.from(Instant.parse("2024-01-03T00:00:00Z")));

        mockMvc.perform(get("/api/v1/fs/search")
                        .param("path", directory.getFileName().toString())
                        .param("q", "type:file (name:Q11 || name:Q3) modified:>=2024-01-02T00:00:00Z")
                        .param("sort", "name").param("dir", "asc").param("size", "1").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("search"))
                .andExpect(jsonPath("$.directories.length()").value(0))
                .andExpect(jsonPath("$.page.totalItems").value(2))
                .andExpect(jsonPath("$.page.totalPages").value(2))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].name").value("Q11.txt"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"name:", "typo:report", "type:unknown", "modified:2026-02-30", "report || typo:value",
            "report || size:-1", "size:1MiB..1MB", "size:9223372036854775808", "extension:>pdf"})
    void invalidQueriesReturnStructuredBadRequestBeforeListingANonexistentRoot(String query) throws Exception {
        mockMvc.perform(get("/api/v1/fs/search").param("path", "not-created").param("q", query))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.notification.message").isNotEmpty())
                .andExpect(jsonPath("$.position").isNumber());
    }

    @Test
    void invalidFieldReportsTheOffsetInTheOriginalQuery() throws Exception {
        mockMvc.perform(get("/api/v1/fs/search").param("q", "  typo:report"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.notification.message").value("Unknown search field: typo"))
                .andExpect(jsonPath("$.position").value(2));
    }

    @Test
    void overlongWhitespaceIsNotAllowedToBypassQueryLimits() throws Exception {
        mockMvc.perform(get("/api/v1/fs/search").param("q", " ".repeat(4097)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.notification.message").value("Search query is too long."));
    }

    @Test
    void blankQueryKeepsTheExistingEmptySearchResponse() throws Exception {
        Path directory = directory();
        Files.writeString(directory.resolve("report.txt"), "hello");
        mockMvc.perform(get("/api/v1/fs/search").param("path", directory.getFileName().toString()).param("q", " \t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.search.performed").value(false))
                .andExpect(jsonPath("$.search.query").value(""))
                .andExpect(jsonPath("$.page.totalItems").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/search/schemas/files", "/api/v1/fs/search",
            "/api/v1/search/schemas/bookmarks", "/api/v1/search/schemas/recent", "/api/v1/search/schemas/sticky-notes",
            "/api/v1/bookmarks", "/api/v1/recent", "/api/v1/sticky-notes/catalog",
            "/api/v1/pending-decisions", "/api/v1/search/schemas/pending-decisions",
            "/api/v1/shares", "/api/v1/file-requests", "/api/v1/search/schemas/shares", "/api/v1/search/schemas/file-requests",
            "/api/v1/trash", "/api/v1/search/schemas/trash"})
    @WithMockUser(roles = "USER")
    void searchAndSchemaRequireAdminRole(String endpoint) throws Exception {
        mockMvc.perform(get(endpoint).param("q", "name:report"))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"files", "pending-decisions", "shares", "file-requests", "trash"})
    @WithAnonymousUser
    void unauthenticatedRequestsCannotReadSchema(String scope) throws Exception {
        mockMvc.perform(get("/api/v1/search/schemas/{scope}", scope))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void unregisteredSearchSchemasAreNotAdvertised() throws Exception {
        mockMvc.perform(get("/api/v1/search/schemas/pending"))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bookmarks", "recent", "sticky-notes", "pending-decisions", "shares", "file-requests", "trash"})
    void connectedDomainsExposeOnlyTheirDeclaredMetadata(String scope) throws Exception {
        var expectedFields = switch (scope) {
            case "bookmarks" -> BookmarkSearchSchema.fields();
            case "recent" -> RecentSearchSchema.fields();
            case "pending-decisions" -> PendingDecisionSearchSchema.fields();
            case "shares" -> ShareLinkSearchSchema.fields();
            case "file-requests" -> FileRequestSearchSchema.fields();
            case "trash" -> TrashSearchSchema.fields();
            default -> StickyNoteSearchSchema.fields();
        };
        var expectedDefaults = switch (scope) {
            case "bookmarks" -> BookmarkSearchSchema.defaultFields();
            case "recent" -> RecentSearchSchema.defaultFields();
            case "pending-decisions" -> PendingDecisionSearchSchema.defaultFields();
            case "shares" -> ShareLinkSearchSchema.defaultFields();
            case "file-requests" -> FileRequestSearchSchema.defaultFields();
            case "trash" -> TrashSearchSchema.defaultFields();
            default -> StickyNoteSearchSchema.defaultFields();
        };
        var response = mockMvc.perform(get("/api/v1/search/schemas/{scope}", scope))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value(scope))
                .andExpect(jsonPath("$.defaultOperator").value("AND"))
                .andExpect(jsonPath("$.limits.maxLength").value(4096))
                .andReturn().getResponse().getContentAsString();
        var schema = objectMapper.readTree(response);
        assertThat(schema.get("fields")).isEqualTo(objectMapper.valueToTree(expectedFields));
        assertThat(schema.get("defaultFields")).isEqualTo(objectMapper.valueToTree(expectedDefaults));
        assertThat(response).doesNotContain(ROOT.toString(), "extractor", "compiler");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/bookmarks", "/api/v1/recent", "/api/v1/sticky-notes/catalog", "/api/v1/pending-decisions",
            "/api/v1/shares", "/api/v1/file-requests", "/api/v1/trash"})
    void domainApisPreserveOriginalErrorPositionsAndWhitespaceLimits(String endpoint) throws Exception {
        mockMvc.perform(get(endpoint).param("q", "  unknown:value").param("directory", "not-created"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.position").value(2))
                .andExpect(jsonPath("$.notification.message").value("Unknown search field: unknown"));
        mockMvc.perform(get(endpoint).param("q", " ".repeat(4097)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.notification.message").value("Search query is too long."));
    }

    @Test
    void bookmarkApiKeepsDescendantsAndNameIsNotTheEntireDefaultSearch() throws Exception {
        var parent = bookmarks.createDirectory(null, "search-" + UUID.randomUUID());
        var nested = bookmarks.createDirectory(parent.id(), "Nested");
        bookmarks.createLink(nested.id(), "Q11 manual", "https://example.com/docs", "review");
        bookmarks.createLink(nested.id(), "Q2 manual", "https://example.com/docs", "review");
        bookmarks.createLink(null, "Q3 manual", "https://example.com/docs", "review");

        mockMvc.perform(get("/api/v1/bookmarks").param("directory", parent.id())
                        .param("q", "manual example type:link note:review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.links[0].title").value("Q11 manual"))
                .andExpect(jsonPath("$.links[1].title").value("Q2 manual"));
        mockMvc.perform(get("/api/v1/bookmarks").param("directory", parent.id()).param("q", "name:example"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
        mockMvc.perform(get("/api/v1/bookmarks").param("directory", parent.id()).param("q", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.directories[0].id").value(nested.id()))
                .andExpect(jsonPath("$.links.length()").value(0));
    }

    @Test
    void recentApiFiltersBeforePagingAndDoesNotExposeHiddenRecords() throws Exception {
        Path directory = directory();
        String path = directory.getFileName().toString();
        for (String name : new String[] {"Q11.txt", "Q2.txt", "Q3.txt"}) {
            Path file = directory.resolve(name);
            Files.writeString(file, "hello");
            Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2024-01-02T00:00:00Z")));
            String vaultPath = path + "/" + name;
            if (name.equals("Q3.txt")) {
                vaultPath = storage.setHiddenVaultPath(vaultPath, true, ConflictPolicy.CANCEL);
            }
            recent.recordVaultPath(vaultPath);
        }
        recent.recordVaultPath(path);
        String query = "path:" + path + " type:file modified:2024-01-02T00:00:00Z extension:TXT size:5";
        mockMvc.perform(get("/api/v1/recent").param("q", query).param("sort", "name").param("dir", "asc")
                        .param("hidden", "hide").param("page", "2").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.search.query").value(query))
                .andExpect(jsonPath("$.page.totalItems").value(2))
                .andExpect(jsonPath("$.directories.length()").value(0))
                .andExpect(jsonPath("$.entries[0].name").value("Q11.txt"));
        mockMvc.perform(get("/api/v1/recent").param("q", query).param("hidden", "show"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalItems").value(3));
    }

    @Test
    void noteCatalogCanSearchFullContentExplicitlyAndRetainsOrphanMetadata() throws Exception {
        Path file = Files.writeString(directory().resolve("target.txt"), "hello");
        String targetKey = ROOT.relativize(file).toString().replace('\\', '/');
        var note = notes.create(new StickyNoteContext(StickyNoteTargetType.STORAGE, targetKey, StickyNoteSurface.DETAIL), 0, 0);
        String marker = "word" + UUID.randomUUID();
        notes.update(note.id(), new StickyNoteSnapshot("x".repeat(120) + " " + marker, 0, 0, null, 280, 220, false, 1));
        mockMvc.perform(get("/api/v1/sticky-notes/catalog").param("q", "content:" + marker + " status:available"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[0].id").value(note.id()))
                .andExpect(jsonPath("$.notes[0].targetExists").value(true));
        Files.delete(file);

        mockMvc.perform(get("/api/v1/sticky-notes/catalog").param("q", marker))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notes.length()").value(0));
        mockMvc.perform(get("/api/v1/sticky-notes/catalog")
                        .param("q", "content:" + marker + " type:storage surface:detail updated:>2020-01-01T00:00:00Z status:orphan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes.length()").value(1))
                .andExpect(jsonPath("$.notes[0].id").value(note.id()))
                .andExpect(jsonPath("$.notes[0].targetExists").value(false))
                .andExpect(jsonPath("$.notes[0].contextLabel").value(targetKey))
                .andExpect(jsonPath("$.notes[0].openUrl").isEmpty());
        mockMvc.perform(get("/api/v1/sticky-notes/catalog").param("q", "content:" + marker + " status:available"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notes.length()").value(0));
        mockMvc.perform(get("/api/v1/sticky-notes/catalog").param("q", "content:" + marker + " || status:unsaved"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.notification.message").value("Invalid value for search field: status"));
    }

    @Test
    void pendingApiFiltersRealRecordsWithoutReleasingStagingOrChangingDecisions() throws Exception {
        String destination = directory().getFileName().toString();
        Path staged = storage.createFileStagingTemporaryFile("search-", ".tmp");
        Files.writeString(staged, "pending");
        var decision = pending.create(staged, PendingFileDecisionSource.FILE_REQUEST, destination,
                "summer holiday.jpg", Files.size(staged), null, "Alice");
        var before = pending.list();
        int notificationCount = objectMapper.readTree(mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .get("actionableCount").asInt();

        mockMvc.perform(get("/api/v1/pending-decisions").param("q", "destination:" + destination
                        + " name:\"summer holiday\" type:file source:file_request submitter:ali status:awaiting_decision size:7B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisions.length()").value(1))
                .andExpect(jsonPath("$.decisions[0].id").value(decision.id()))
                .andExpect(jsonPath("$.decisions[0].destinationLabel").value("/" + destination))
                .andExpect(jsonPath("$.decisions[0].createdAt").value(decision.createdAt().toString()));
        mockMvc.perform(get("/api/v1/pending-decisions").param("q", "destination:" + destination + " status:paused"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decisions.length()").value(0));
        mockMvc.perform(get("/api/v1/pending-decisions").param("q", "destination:" + destination + " name:missing"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decisions.length()").value(0));
        mockMvc.perform(get("/api/v1/pending-decisions").param("q", "destination:" + destination + " size:>7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decisions.length()").value(0));
        mockMvc.perform(get("/api/v1/pending-decisions").param("q", "name:summer || source:unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.notification.message").value("Invalid value for search field: source"));

        assertThat(pending.list()).isEqualTo(before);
        mockMvc.perform(get("/api/v1/notifications").param("q", "name:missing"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actionableCount").value(notificationCount));
        assertThat(staged).exists();
        assertThat(Files.readString(staged)).isEqualTo("pending");
    }

    @Test
    void fileSizeAndExactExtensionFilterBeforePaginationAndRespectHiddenPolicy() throws Exception {
        Path directory = directory();
        String path = directory.getFileName().toString();
        Files.write(directory.resolve("Q2.PDF"), new byte[1024]);
        Files.write(directory.resolve("Q11.pdf"), new byte[2048]);
        Files.write(directory.resolve("excluded.pdfx"), new byte[1024]);
        Files.write(directory.resolve("small.pdf"), new byte[1]);
        Files.createDirectory(directory.resolve("folder.pdf"));
        Files.write(directory.resolve("hidden.pdf"), new byte[1024]);
        storage.setHiddenVaultPath(path + "/hidden.pdf", true, ConflictPolicy.CANCEL);
        String query = "extension:PDF size:1KB..2KiB";
        mockMvc.perform(get("/api/v1/fs/search").param("path", path).param("q", query)
                        .param("hidden", "hide").param("sort", "name").param("dir", "asc").param("page", "2").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalItems").value(2))
                .andExpect(jsonPath("$.directories.length()").value(0))
                .andExpect(jsonPath("$.entries[0].name").value("Q11.pdf"));
        mockMvc.perform(get("/api/v1/fs/search").param("path", path).param("q", query).param("hidden", "show"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalItems").value(3));
        mockMvc.perform(get("/api/v1/fs/search").param("path", path).param("q", "type:directory size:0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.directories.length()").value(0));
        Files.write(directory.resolve("Q2.PDF"), new byte[4]);
        mockMvc.perform(get("/api/v1/fs/search").param("path", path).param("q", "name:Q2 size:4"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalItems").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/fs/search", "/api/v1/recent", "/api/v1/pending-decisions"})
    void invalidSizeIsValidatedInEveryConnectedApiBeforeDataReads(String endpoint) throws Exception {
        mockMvc.perform(get(endpoint).param("path", "not-created").param("q", "  name:file || size:1XB"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.position").value(15))
                .andExpect(jsonPath("$.notification.message").isNotEmpty());
    }

    @Test
    void shareQueriesPreserveOrderingAndPayloadAndUseStoredMetadataWithoutReadingTargets() throws Exception {
        Path directory = directory();
        String path = directory.getFileName().toString();
        Files.writeString(directory.resolve("summer holiday.jpg"), "image");
        var active = shares.create(path, "summer holiday.jpg", null, null, true);
        var expired = shares.create(path, "summer holiday.jpg", Instant.EPOCH, null, false);
        var revoked = shares.create(path, "summer holiday.jpg", Instant.EPOCH, null, true);
        shares.revoke(revoked.token());
        var before = shares.list();
        Files.delete(directory.resolve("summer holiday.jpg"));

        var response = mockMvc.perform(get("/api/v1/shares").param("q", "path:" + path))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var rows = objectMapper.readTree(response);
        assertThat(rows.isArray()).isTrue();
        assertThat(rows).extracting(row -> row.get("token").asText())
                .containsExactlyElementsOf(before.stream().filter(link -> link.path().startsWith(path + "/"))
                        .map(link -> link.token()).toList());
        mockMvc.perform(get("/api/v1/shares").param("q", "path:" + path
                        + " name:\"summer holiday\" type:file preview:on status:active created:>2020-01-01T00:00:00Z"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].token").value(active.token()))
                .andExpect(jsonPath("$[0].statusLabel").value("Active"))
                .andExpect(jsonPath("$[0].active").value(true));
        mockMvc.perform(get("/api/v1/shares").param("q", "path:" + path + " status:expired expires:<=1970-01-01T00:00:00Z"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].token").value(expired.token()));
        mockMvc.perform(get("/api/v1/shares").param("q", "path:" + path + " status:revoked"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].token").value(revoked.token()));
        mockMvc.perform(get("/api/v1/shares").param("q", "path:" + path + " name:missing"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertThat(shares.list()).isEqualTo(before);
    }

    @Test
    void requestQueriesFilterOnlyIssuedRequestsAndPreserveDuplicateCreationDefaultsAndQuota() throws Exception {
        String destination = directory().getFileName().toString();
        var original = fileRequests.create("Summer holiday", "Please send originals", destination,
                UploaderNamePolicy.REQUIRED, 100, 200, 2, List.of("jpg"), 0, null);
        var revoked = fileRequests.create("Other title", destination, UploaderNamePolicy.NONE,
                100, 200, 2, List.of(), 0, null);
        fileRequests.revoke(revoked.id());
        fileRequests.recordAcceptedUpload(original.id(), UUID.randomUUID().toString(), 100);
        fileRequests.recordAcceptedUpload(original.id(), UUID.randomUUID().toString(), 100);
        var before = fileRequests.list();
        var unfiltered = objectMapper.readTree(mockMvc.perform(get("/api/v1/file-requests")
                        .param("copyFrom", original.id()).param("destinationPath", destination))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        var filtered = objectMapper.readTree(mockMvc.perform(get("/api/v1/file-requests")
                        .param("copyFrom", original.id()).param("destinationPath", destination)
                        .param("q", "name:missing"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requests.length()").value(0))
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(filtered.get("defaults")).isEqualTo(unfiltered.get("defaults"));
        assertThat(filtered.get("defaults").get("duplicating").asBoolean()).isTrue();
        mockMvc.perform(get("/api/v1/file-requests").param("q", "destination:" + destination
                        + " name:summer description:originals status:FULL uploader-policy:required created:>2020-01-01T00:00:00Z"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requests.length()").value(1))
                .andExpect(jsonPath("$.requests[0].id").value(original.id()))
                .andExpect(jsonPath("$.requests[0].statusLabel").value("Full"))
                .andExpect(jsonPath("$.requests[0].statusClass").value("warning"))
                .andExpect(jsonPath("$.requests[0].active").value(false));
        mockMvc.perform(get("/api/v1/file-requests").param("q", "destination:" + destination + " status:revoked"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requests[0].id").value(revoked.id()));
        var ordered = objectMapper.readTree(mockMvc.perform(get("/api/v1/file-requests")
                        .param("q", "destination:" + destination))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).get("requests");
        assertThat(ordered).extracting(row -> row.get("id").asText())
                .containsExactlyElementsOf(before.stream().filter(item -> destination.equals(item.destinationPath()))
                        .map(item -> item.id()).toList());
        assertThat(fileRequests.list()).isEqualTo(before);
    }

    @Test
    void requestQueryErrorsTakePrecedenceOverInvalidCreationContext() throws Exception {
        mockMvc.perform(get("/api/v1/file-requests").param("destinationPath", "not-created")
                        .param("copyFrom", "not-created").param("q", "name:any || status:invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.notification.message").value("Invalid value for search field: status"));
    }

    @Test
    void trashSearchUsesOriginalMetadataAndKeepsOrderPayloadAndRestoreSemantics() throws Exception {
        Path directory = directory();
        String parent = directory.getFileName().toString();
        Files.writeString(directory.resolve("summer holiday.PDF"), "first");
        Files.writeString(directory.resolve("other.txt"), "second");
        Files.createDirectory(directory.resolve("folder.pdf"));
        var records = trash.moveToTrash(parent, List.of("summer holiday.PDF", "other.txt", "folder.pdf"));
        var before = trashRecords.list();
        assertThat(directory.resolve("summer holiday.PDF")).doesNotExist();
        var full = objectMapper.readTree(mockMvc.perform(get("/api/v1/trash").param("q", "path:" + parent))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3))
                .andReturn().getResponse().getContentAsByteArray()).get("items");
        assertThat(full).extracting(row -> row.get("id").asText()).containsExactlyElementsOf(
                before.stream().filter(record -> record.originalParentPath().equals(parent)).map(TrashRecord::id).toList());
        var filtered = objectMapper.readTree(mockMvc.perform(get("/api/v1/trash").param("q", "path:" + parent
                        + " name:\"summer holiday\" type:file extension:pdf deleted:>=2020-01-01 expires:>2020-01-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(records.get(0).id()))
                .andExpect(jsonPath("$.items[0].originalName").value("summer holiday.PDF"))
                .andExpect(jsonPath("$.items[0].originalPath").value(parent + "/summer holiday.PDF"))
                .andExpect(jsonPath("$.items[0].trashName").doesNotExist())
                .andReturn().getResponse().getContentAsByteArray()).get("items").get(0);
        assertThat(full).contains(filtered);
        mockMvc.perform(get("/api/v1/trash").param("q", parent + " type:directory"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(records.get(2).id()));
        mockMvc.perform(get("/api/v1/trash").param("q", "name:missing-" + parent))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        assertThat(trashRecords.list()).isEqualTo(before);
        mockMvc.perform(post("/api/v1/trash/restore").with(csrf()).param("id", records.get(1).id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        assertThat(directory.resolve("other.txt")).hasContent("second");
        assertThat(trashRecords.find(records.get(0).id())).isPresent();
    }

    @Test
    void invalidTrashQueryLeavesStaleMetadataUntouchedButValidListingKeepsExistingCleanup() throws Exception {
        String id = UUID.randomUUID().toString();
        var stale = new TrashRecord(id, "removed/original.txt", "removed", "original.txt", id,
                false, 1, "1 B", "File", Instant.now(), Instant.now().minusSeconds(60));
        trashRecords.add(stale);
        mockMvc.perform(get("/api/v1/trash").param("q", "name:any || type:invalid"))
                .andExpect(status().isBadRequest());
        assertThat(trashRecords.find(id)).contains(stale);
        mockMvc.perform(get("/api/v1/trash").param("q", "name:missing"))
                .andExpect(status().isOk());
        assertThat(trashRecords.find(id)).isEmpty();
    }

    private Path directory() throws IOException {
        return Files.createDirectory(ROOT.resolve("search-" + UUID.randomUUID()));
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("endervault-search-flow-");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
