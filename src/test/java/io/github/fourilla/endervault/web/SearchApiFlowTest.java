package io.github.fourilla.endervault.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
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
                .andExpect(jsonPath("$.fields.length()").value(4))
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
    @ValueSource(strings = {"name:", "typo:report", "type:unknown", "modified:2026-02-30", "report || typo:value"})
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
    @ValueSource(strings = {"/api/v1/search/schemas/files", "/api/v1/fs/search"})
    @WithMockUser(roles = "USER")
    void searchAndSchemaRequireAdminRole(String endpoint) throws Exception {
        mockMvc.perform(get(endpoint).param("q", "name:report"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void unauthenticatedRequestsCannotReadSchema() throws Exception {
        mockMvc.perform(get("/api/v1/search/schemas/files"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void unregisteredSearchSchemasAreNotAdvertised() throws Exception {
        mockMvc.perform(get("/api/v1/search/schemas/pending"))
                .andExpect(status().isNotFound());
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
