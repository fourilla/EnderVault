package io.github.fourilla.endervault.web.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.share.ShareLink;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.StorageService;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@WithAnonymousUser
class SharedComicFlowTest {
    private static final Path ROOT = temporaryRoot();
    @Autowired MockMvc mockMvc;
    @Autowired ShareLinkService shares;
    @Autowired StorageService storage;
    @Autowired ObjectMapper mapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nas.storage.root", ROOT::toString);
        registry.add("nas.file-tools.comic-max-pages", () -> 4);
        registry.add("nas.file-tools.comic-page-max-bytes", () -> 1024);
    }

    @Test
    void singleFileViewerUsesCompactTokenPayloadAndNaturalPageOrder() throws Exception {
        String directory = "private-series-" + System.nanoTime();
        Path file = ROOT.resolve(directory).resolve("book.cbz");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("11.png", new byte[] {11});
        entries.put("3.png", new byte[] {3});
        entries.put("info.txt", "Title: <script>bad()</script>".getBytes(StandardCharsets.UTF_8));
        writeComic(file, entries);
        ShareLink link = shares.create(directory, "book.cbz", null);

        mockMvc.perform(get("/s/{token}", link.token()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"shared-comic-root\"")))
                .andExpect(content().string(containsString("/react/assets/sharedComic-")))
                .andExpect(content().string(containsString("data-manifest-url=\"/s/" + link.token() + "/comic/manifest\"")))
                .andExpect(content().string(not(containsString("/react/assets/adminApp-"))))
                .andExpect(content().string(not(containsString("/react/assets/shell-"))))
                .andExpect(content().string(not(containsString("/react/assets/fileTools-"))))
                .andExpect(content().string(not(containsString(directory))));
        String response = mockMvc.perform(get("/s/{token}/comic/manifest", link.token())
                        .param("path", "../unshared").param("item", "other.cbz").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageCount").value(2))
                .andExpect(jsonPath("$.metadata.rawText").value("Title: <script>bad()</script>"))
                .andExpect(jsonPath("$.pageUrl").value("/s/" + link.token() + "/comic/page"))
                .andExpect(jsonPath("$.pages").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(response).size()).isEqualTo(4);
        mockMvc.perform(get("/s/{token}/comic/page", link.token()).param("page", "0"))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[] {3}))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mockMvc.perform(get("/s/{token}/comic/preview", link.token()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("comic-reader-pages")));
    }

    @Test
    void directoryViewerPreservesEncodedRelativeScopeInRealRequests() throws Exception {
        String directory = "public-series-" + System.nanoTime();
        String nested = "chapter + 1/part & 2";
        String name = "book + 01.cbz";
        writeComic(ROOT.resolve(directory).resolve(nested).resolve(name), Map.of("1.png", new byte[] {7}));
        ShareLink link = shares.create("", directory, null);
        String manifestUrl = SharedFileRoutes.comicManifestUrl(link.token(), nested, name);
        mockMvc.perform(get("/s/{token}/file", link.token()).param("path", nested).param("item", name))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(manifestUrl.replace("&", "&amp;"))))
                .andExpect(content().string(not(containsString("/files/detail/comic"))));
        JsonNode body = mapper.readTree(mockMvc.perform(get(URI.create(manifestUrl)).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value(name))
                .andReturn().getResponse().getContentAsString());
        mockMvc.perform(get(URI.create(body.get("pageUrl").asText() + "&page=0")))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[] {7}));
    }

    @Test
    void directoryViewerRejectsOutsideHiddenAndNonComicTargets() throws Exception {
        String directory = "restricted-series-" + System.nanoTime();
        writeComic(ROOT.resolve(directory).resolve("secret/book.cbz"), Map.of("1.png", new byte[] {1}));
        Files.writeString(ROOT.resolve(directory).resolve("plain.txt"), "text");
        ShareLink link = shares.create("", directory, null);
        String hidden = storage.setHiddenVaultPath(directory + "/secret", true, ConflictPolicy.CANCEL)
                .substring(directory.length() + 1);
        for (String endpoint : new String[] {"manifest", "page"}) {
            mockMvc.perform(get("/s/{token}/comic/" + endpoint, link.token()).param("path", "../outside")
                            .param("item", "book.cbz").param("page", "0").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/s/{token}/comic/" + endpoint, link.token()).param("path", hidden)
                            .param("item", "book.cbz").param("page", "0").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/s/{token}/comic/" + endpoint, link.token()).param("item", "plain.txt")
                            .param("page", "0").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void cachedComicCannotBeRequestedAfterRevokeOrExpiry() throws Exception {
        String name = "revoked-" + System.nanoTime() + ".cbz";
        writeComic(ROOT.resolve(name), Map.of("1.png", new byte[] {1}));
        ShareLink revoked = shares.create("", name, null);
        mockMvc.perform(get("/s/{token}/comic/manifest", revoked.token()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        shares.revoke(revoked.token());
        ShareLink expired = shares.create("", name, Instant.now().minusSeconds(60));
        for (ShareLink link : new ShareLink[] {revoked, expired}) {
            for (String endpoint : new String[] {"manifest", "page", "preview"}) {
                mockMvc.perform(get("/s/{token}/comic/" + endpoint, link.token()).param("page", "0")
                                .accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.notification.message").value("Share link is unavailable."));
            }
        }
    }

    @Test
    void corruptComicKeepsLandingAndDownloadAvailable() throws Exception {
        String name = "broken-" + System.nanoTime() + ".cbz";
        Files.writeString(ROOT.resolve(name), "not a zip");
        ShareLink link = shares.create("", name, null);
        mockMvc.perform(get("/s/{token}", link.token())).andExpect(status().isOk())
                .andExpect(content().string(containsString("shared-download-button")));
        mockMvc.perform(get("/s/{token}/comic/manifest", link.token()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.notification.message").value("This CBZ file could not be opened as a ZIP archive."));
        mockMvc.perform(get("/s/{token}/download", link.token())).andExpect(status().isOk())
                .andExpect(content().string("not a zip"));
    }

    @Test
    void publicViewerRetainsPageAndArchiveLimits() throws Exception {
        String name = "limits-" + System.nanoTime() + ".cbz";
        writeComic(ROOT.resolve(name), Map.of("1.png", new byte[1025]));
        ShareLink link = shares.create("", name, null);
        for (String page : new String[] {"-1", "1", "0"}) {
            mockMvc.perform(get("/s/{token}/comic/page", link.token()).param("page", page).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());
        }
        writeComic(ROOT.resolve(name), Map.of("1.png", new byte[1], "2.png", new byte[1],
                "3.png", new byte[1], "4.png", new byte[1], "5.png", new byte[1]));
        mockMvc.perform(get("/s/{token}/comic/manifest", link.token()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.notification.message").value("This CBZ contains more than 4 image pages."));
    }

    @Test
    void emptyArchiveHasNoPagesAndNonComicLandingDoesNotLoadComicBundle() throws Exception {
        String name = "empty-" + System.nanoTime() + ".cbz";
        writeComic(ROOT.resolve(name), Map.of("info.txt", "No pages".getBytes(StandardCharsets.UTF_8)));
        ShareLink link = shares.create("", name, null);
        mockMvc.perform(get("/s/{token}/comic/manifest", link.token()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pageCount").value(0));
        String text = "unrelated-" + System.nanoTime() + ".txt";
        Files.writeString(ROOT.resolve(text), "text");
        ShareLink textLink = shares.create("", text, null);
        mockMvc.perform(get("/s/{token}", textLink.token())).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("shared-comic-root"))))
                .andExpect(content().string(not(containsString("/react/assets/sharedComic-"))));
    }

    @Test
    void disabledSharedPreviewsRemoveTheLandingSurfaceAndRejectDirectPreviewRequests() throws Exception {
        String name = "disabled-" + System.nanoTime() + ".cbz";
        writeComic(ROOT.resolve(name), Map.of("1.png", new byte[] {1}));
        ShareLink link = shares.create("", name, null, null, false);

        mockMvc.perform(get("/s/{token}", link.token()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("shared-preview-panel"))))
                .andExpect(content().string(not(containsString("shared-comic-root"))))
                .andExpect(content().string(not(containsString("/react/assets/sharedComic-"))))
                .andExpect(content().string(containsString("shared-download-button")));
        mockMvc.perform(get("/s/{token}/comic/manifest", link.token()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.notification.message").value("Shared preview is unavailable."));
        mockMvc.perform(get("/s/{token}/preview", link.token()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/s/{token}/download", link.token()))
                .andExpect(status().isOk());
    }

    private static void writeComic(Path path, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("endervault-shared-comic-"); }
        catch (IOException ex) { throw new IllegalStateException(ex); }
    }
}
