package io.github.fourilla.endervault.web.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@WithAnonymousUser
class SharedBrowserApiTest {

    private static final Path ROOT = temporaryRoot();
    @Autowired MockMvc mockMvc;
    @Autowired ShareLinkService shares;
    @Autowired StorageService storage;
    @Autowired NasProperties properties;
    @Autowired ObjectMapper mapper;
    @Autowired ActivityLogService activity;
    @Autowired PublicLinkTokenService tokens;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nas.storage.root", ROOT::toString);
        registry.add("nas.share.enabled", () -> true);
        registry.add("nas.share.directory-share-enabled", () -> true);
        registry.add("nas.share.directory-show-hidden-items", () -> false);
        registry.add("nas.activity-log.enabled", () -> true);
        registry.add("nas.file-tools.text-auto-load-max-bytes", () -> 1024);
        registry.add("nas.file-tools.text-manual-load-max-bytes", () -> 2048);
    }

    @Test
    void anonymousListingReturnsOnlyRelativePublicInformationInExistingOrder() throws Exception {
        String privatePrefix = directory("private-parent");
        String sharedPath = privatePrefix + "/published";
        Files.createDirectories(ROOT.resolve(sharedPath + "/chapter 11"));
        Files.createDirectories(ROOT.resolve(sharedPath + "/chapter 2"));
        write(sharedPath + "/note 11.txt", "eleven");
        write(sharedPath + "/note 2.txt", "two");
        write(privatePrefix + "/outside-secret.txt", "private");
        ShareLink link = share(sharedPath, true);

        String response = mockMvc.perform(get("/s/{token}/listing.json", link.token()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.targetType").value("DIRECTORY"))
                .andExpect(jsonPath("$.view").value("listing"))
                .andExpect(jsonPath("$.path").value(""))
                .andExpect(jsonPath("$.parentPath").doesNotExist())
                .andExpect(jsonPath("$.upUrl").doesNotExist())
                .andExpect(jsonPath("$.breadcrumbs[0].path").value(""))
                .andExpect(jsonPath("$.breadcrumbs[0].url").value("/s/" + link.token()))
                .andExpect(jsonPath("$.entries.length()").value(4))
                .andExpect(jsonPath("$.entries[0].path").value("chapter 2"))
                .andExpect(jsonPath("$.entries[0].directory").value(true))
                .andExpect(jsonPath("$.entries[0].downloadUrl").doesNotExist())
                .andExpect(jsonPath("$.entries[1].name").value("chapter 11"))
                .andExpect(jsonPath("$.entries[2].name").value("note 2.txt"))
                .andExpect(jsonPath("$.entries[2].previewLandingUrl").value(
                        "/s/" + link.token() + "/file?item=note%202.txt"))
                .andExpect(jsonPath("$.downloadZipUrl").value("/s/" + link.token() + "/download.zip"))
                .andReturn().getResponse().getContentAsString();
        JsonNode body = mapper.readTree(response);
        assertThat(body.size()).isEqualTo(9);
        assertThat(body.get("entries").get(2).size()).isEqualTo(12);
        assertThat(response).doesNotContain(privatePrefix, "outside-secret", "/files/", "/api/",
                "createdAt", "expiresAt", "capabilities", "favorite");
    }

    @Test
    void nestedListingAndDetailKeepCanonicalScopeAndEncodedPublicUrls() throws Exception {
        String directory = directory("encoded");
        String nested = "chapter + & 1/50% 한글";
        String name = "note + % # 1.txt";
        write(directory + "/" + nested + "/" + name, "shared text");
        ShareLink link = share(directory, true);

        JsonNode listing = mapper.readTree(mockMvc.perform(get("/s/{token}/listing.json", link.token())
                        .param("path", nested.replace('/', '\\')))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value(nested))
                .andExpect(jsonPath("$.parentPath").value("chapter + & 1"))
                .andExpect(jsonPath("$.breadcrumbs.length()").value(3))
                .andExpect(jsonPath("$.entries[0].path").value(nested + "/" + name))
                .andReturn().getResponse().getContentAsString());
        JsonNode detail = mapper.readTree(mockMvc.perform(get("/s/{token}/detail.json", link.token())
                        .param("path", nested.replace('/', '\\')).param("item", name))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value(nested + "/" + name))
                .andExpect(jsonPath("$.parentPath").value(nested))
                .andExpect(jsonPath("$.text.content").value("shared text"))
                .andExpect(jsonPath("$.toolLabel").value("Text Preview"))
                .andReturn().getResponse().getContentAsString());
        mockMvc.perform(get(URI.create(listing.get("entries").get(0).get("downloadUrl").asText())))
                .andExpect(status().isOk()).andExpect(content().string("shared text"));
        mockMvc.perform(get(URI.create(detail.get("previewContentUrl").asText())))
                .andExpect(status().isOk()).andExpect(content().string("shared text"));
        assertThat(detail.size()).isEqualTo(18);
        assertThat(detail.get("text").size()).isEqualTo(3);
        assertThat(detail.toString()).doesNotContain(directory, "editable", "manualLoadAvailable", "capabilities");
        assertThat(detail.get("downloadUrl").asText()).contains("%2B", "%25", "%26", "%2F");
        mockMvc.perform(get("/s/{token}/listing.json", link.token()).param("path", "chapter + & 1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.parentPath").value(""))
                .andExpect(jsonPath("$.upUrl").value("/s/" + link.token()));
    }

    @Test
    void directFileTokenNeverUsesRequestedSiblingAndCannotList() throws Exception {
        String directory = directory("single-private");
        write(directory + "/shared.txt", "only this file");
        write(directory + "/private.txt", "private sibling");
        ShareLink link = share(directory + "/shared.txt", true);

        String response = mockMvc.perform(get("/s/{token}/detail.json", link.token())
                        .param("path", "../outside").param("item", "private.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.targetType").value("FILE"))
                .andExpect(jsonPath("$.view").value("detail"))
                .andExpect(jsonPath("$.path").value(""))
                .andExpect(jsonPath("$.parentPath").doesNotExist())
                .andExpect(jsonPath("$.upUrl").doesNotExist())
                .andExpect(jsonPath("$.name").value("shared.txt"))
                .andExpect(jsonPath("$.text.content").value("only this file"))
                .andExpect(jsonPath("$.previewContentUrl").value("/s/" + link.token() + "/preview"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(directory, "private sibling", "private.txt", "../outside");
        unavailable(link.token(), "listing.json", 404);
    }

    @Test
    void firewallRestrictedFilenamesUseExistingScopedQueryDownload() throws Exception {
        String directory = directory("firewall-download");
        String nested = "chapter + 50%";
        String[] names = {"percent %.txt", "semicolon ;.txt"};
        for (String name : names) {
            write(directory + "/" + nested + "/" + name, "download " + name);
        }
        ShareLink directoryLink = share(directory, false);
        for (String name : names) {
            JsonNode detail = mapper.readTree(mockMvc.perform(get("/s/{token}/detail.json", directoryLink.token())
                            .param("path", nested).param("item", name))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            URI directoryDownload = URI.create(detail.get("downloadUrl").asText());
            assertThat(directoryDownload.getRawPath()).isEqualTo("/s/" + directoryLink.token() + "/download");
            assertThat(directoryDownload.getRawQuery()).contains("path=", "item=");
            mockMvc.perform(get(directoryDownload))
                    .andExpect(status().isOk()).andExpect(content().string("download " + name));

            ShareLink fileLink = share(directory + "/" + nested + "/" + name, false);
            JsonNode fileDetail = mapper.readTree(mockMvc.perform(get("/s/{token}/detail.json", fileLink.token()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            String fileDownload = fileDetail.get("downloadUrl").asText();
            assertThat(fileDownload).isEqualTo("/s/" + fileLink.token() + "/download");
            mockMvc.perform(get(URI.create(fileDownload)))
                    .andExpect(status().isOk()).andExpect(content().string("download " + name));
        }
    }

    @Test
    void directoryTraversalMissingAndMalformedItemsProduceSanitizedJsonWithoutAcceptHeader() throws Exception {
        String directory = directory("restricted");
        write(directory + "/visible.txt", "visible");
        ShareLink link = share(directory, true);

        for (String path : new String[] {"../outside", "/outside", "a//b", "C:/outside"}) {
            mockMvc.perform(get("/s/{token}/listing.json", link.token()).param("path", path))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(header().string("Cache-Control", containsString("no-store")))
                    .andExpect(jsonPath("$.notification.message").value("Shared content is unavailable."));
        }
        for (String item : new String[] {"../visible.txt", "child/visible.txt"}) {
            mockMvc.perform(get("/s/{token}/detail.json", link.token()).param("item", item))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.notification.message").value("Shared content is unavailable."));
        }
        String missing = mockMvc.perform(get("/s/{token}/detail.json", link.token()).param("item", "missing.txt"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.notification.message").value("Shared content is unavailable."))
                .andReturn().getResponse().getContentAsString();
        assertThat(missing).doesNotContain(ROOT.toString(), directory, "missing.txt");
        mockMvc.perform(get("/s/{token}/detail.json", link.token()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.notification.message").value("Invalid shared content request."));
    }

    @Test
    void hiddenAncestorsAndFilesUseCurrentDirectorySharePolicyOnEveryRequest() throws Exception {
        String directory = directory("hidden");
        write(directory + "/secret/inside.txt", "nested secret");
        write(directory + "/hidden.txt", "hidden file");
        write(directory + "/visible.txt", "visible");
        ShareLink link = share(directory, true);
        String hiddenDirectory = storage.setHiddenVaultPath(directory + "/secret", true, ConflictPolicy.CANCEL)
                .substring(directory.length() + 1);
        String hiddenFile = storage.setHiddenVaultPath(directory + "/hidden.txt", true, ConflictPolicy.CANCEL)
                .substring(directory.length() + 1);

        mockMvc.perform(get("/s/{token}/listing.json", link.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries.length()").value(1));
        mockMvc.perform(get("/s/{token}/listing.json", link.token()).param("path", hiddenDirectory))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/s/{token}/detail.json", link.token()).param("path", hiddenDirectory)
                        .param("item", "inside.txt"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/s/{token}/detail.json", link.token()).param("item", hiddenFile))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/s/{token}", link.token()).param("path", hiddenDirectory))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/s/{token}/file", link.token()).param("path", hiddenDirectory).param("item", "inside.txt"))
                .andExpect(status().isNotFound());

        boolean previous = properties.getShare().isDirectoryShowHiddenItems();
        properties.getShare().setDirectoryShowHiddenItems(true);
        try {
            mockMvc.perform(get("/s/{token}/listing.json", link.token()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.entries.length()").value(3));
            mockMvc.perform(get("/s/{token}/detail.json", link.token()).param("path", hiddenDirectory)
                            .param("item", "inside.txt"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.text.content").value("nested secret"));
            mockMvc.perform(get("/s/{token}/detail.json", link.token()).param("item", hiddenFile))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.text.content").value("hidden file"));
            mockMvc.perform(get("/s/{token}/file", link.token()).param("path", hiddenDirectory).param("item", "inside.txt"))
                    .andExpect(status().isOk()).andExpect(content().string(containsString("id=\"public-share-root\"")));
        } finally {
            properties.getShare().setDirectoryShowHiddenItems(previous);
        }
        mockMvc.perform(get("/s/{token}/detail.json", link.token()).param("item", hiddenFile))
                .andExpect(status().isNotFound());
    }

    @Test
    void disabledPreviewAndUnsupportedToolsNeverExposePreviewContent() throws Exception {
        String directory = directory("preview-policy");
        write(directory + "/plain.txt", "not to preview");
        write(directory + "/document.pdf", "%PDF-1.7");
        write(directory + "/archive.zip", "not an archive");
        ShareLink disabled = share(directory, false);
        mockMvc.perform(get("/s/{token}/listing.json", disabled.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[2].detailUrl").isNotEmpty())
                .andExpect(jsonPath("$.entries[2].downloadUrl").isNotEmpty())
                .andExpect(jsonPath("$.entries[2].previewLandingUrl").doesNotExist());
        mockMvc.perform(get("/s/{token}/detail.json", disabled.token()).param("item", "plain.txt"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.previewEnabled").value(false))
                .andExpect(jsonPath("$.previewContentUrl").doesNotExist())
                .andExpect(jsonPath("$.comicManifestUrl").doesNotExist())
                .andExpect(jsonPath("$.text").doesNotExist())
                .andExpect(jsonPath("$.downloadUrl").isNotEmpty());
        ShareLink enabled = share(directory, true);
        for (String name : new String[] {"document.pdf", "archive.zip"}) {
            mockMvc.perform(get("/s/{token}/detail.json", enabled.token()).param("item", name))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.previewEnabled").value(false))
                    .andExpect(jsonPath("$.previewContentUrl").doesNotExist())
                    .andExpect(jsonPath("$.comicManifestUrl").doesNotExist())
                    .andExpect(jsonPath("$.text").doesNotExist());
        }
    }

    @Test
    void allowedMediaAndComicUseExistingTokenScopedPreviewUrls() throws Exception {
        String directory = directory("supported-preview");
        for (String name : new String[] {"photo.png", "song.mp3", "movie.mp4", "book.cbz"}) {
            write(directory + "/" + name, "content need not be scanned during the query");
        }
        ShareLink link = share(directory, true);
        for (String name : new String[] {"photo.png", "song.mp3", "movie.mp4"}) {
            mockMvc.perform(get("/s/{token}/detail.json", link.token()).param("item", name))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.previewEnabled").value(true))
                    .andExpect(jsonPath("$.previewContentUrl").value("/s/" + link.token() + "/preview?item=" + name))
                    .andExpect(jsonPath("$.text").doesNotExist());
        }
        mockMvc.perform(get("/s/{token}/detail.json", link.token()).param("item", "book.cbz"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.toolType").value("comic"))
                .andExpect(jsonPath("$.previewEnabled").value(true))
                .andExpect(jsonPath("$.comicManifestUrl").value("/s/" + link.token() + "/comic/manifest?item=book.cbz"))
                .andExpect(jsonPath("$.previewContentUrl").value("/s/" + link.token() + "/comic/preview?item=book.cbz"));
    }

    @Test
    void textSizeAndUtf8LimitsRemainReadOnlyPublicProjections() throws Exception {
        String directory = directory("text-limits");
        write(directory + "/manual.txt", "x".repeat(1025));
        write(directory + "/too-large.txt", "x".repeat(2049));
        Files.write(ROOT.resolve(directory + "/invalid.txt"), new byte[] {(byte) 0xc3, (byte) 0x28});
        ShareLink link = share(directory, true);
        for (String name : new String[] {"manual.txt", "too-large.txt", "invalid.txt"}) {
            String response = mockMvc.perform(get("/s/{token}/detail.json", link.token()).param("item", name))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.previewEnabled").value(true))
                    .andExpect(jsonPath("$.text.loaded").value(false))
                    .andExpect(jsonPath("$.text.content").value(""))
                    .andExpect(jsonPath("$.text.message").isNotEmpty())
                    .andReturn().getResponse().getContentAsString();
            assertThat(mapper.readTree(response).get("text").size()).isEqualTo(3);
            assertThat(response).doesNotContain("editable", "manualLoadAvailable", "/files/");
        }
    }

    @Test
    void tokensRemainCaseSensitiveAndAreRevalidatedAfterRevokeExpiryAndGlobalDisable() throws Exception {
        String directory = directory("token-policy");
        write(directory + "/plain.txt", "text");
        ShareLink mixedCase = shares.createForVaultPath(directory, null, "MiXeDToken" + System.nanoTime(), true);
        mockMvc.perform(get("/s/{token}/listing.json", mixedCase.token())).andExpect(status().isOk());
        unavailable(mixedCase.token().toLowerCase(Locale.ROOT), "listing.json", 404);
        unavailable(mixedCase.token().toLowerCase(Locale.ROOT), "detail.json", 404);
        shares.revoke(mixedCase.token());
        ShareLink expired = shares.createForVaultPath(directory, Instant.now().minusSeconds(60), null, true);
        for (ShareLink link : new ShareLink[] {mixedCase, expired}) {
            unavailable(link.token(), "listing.json", 404);
            unavailable(link.token(), "detail.json", 404);
        }
        ShareLink active = share(directory, true);
        boolean previous = properties.getShare().isEnabled();
        properties.getShare().setEnabled(false);
        try {
            unavailable(active.token(), "listing.json", 403);
            unavailable(active.token(), "detail.json", 403);
        } finally {
            properties.getShare().setEnabled(previous);
        }
    }

    @Test
    void rootRemovalOrChangedFileTargetCannotExposeSiblingDirectory() throws Exception {
        String directory = directory("root-revalidation");
        write(directory + "/plain.txt", "text");
        ShareLink directoryLink = share(directory, true);
        ShareLink fileLink = share(directory + "/plain.txt", true);
        mockMvc.perform(get("/s/{token}/detail.json", fileLink.token())).andExpect(status().isOk());
        Files.delete(ROOT.resolve(directory + "/plain.txt"));
        Files.createDirectory(ROOT.resolve(directory + "/plain.txt"));
        write(directory + "/plain.txt/private.txt", "private");
        unavailable(fileLink.token(), "detail.json", 404);
        Files.move(ROOT.resolve(directory), ROOT.resolve(directory + "-moved"));
        unavailable(directoryLink.token(), "listing.json", 404);
        unavailable(directoryLink.token(), "detail.json", 404);
    }

    @Test
    void htmlHostsExposeOnlyMinimalPublicBootstrapAndValidateBothLandingKinds() throws Exception {
        String privatePrefix = directory("host-private");
        String directory = privatePrefix + "/published";
        write(directory + "/plain.txt", "private text is fetched only through the JSON view");
        ShareLink directoryLink = share(directory, true);
        ShareLink fileLink = share(directory + "/plain.txt", true);
        for (ShareLink link : new ShareLink[] {directoryLink, fileLink}) {
            String html = mockMvc.perform(get("/s/{token}", link.token()))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", containsString("no-store")))
                    .andExpect(content().string(containsString("id=\"public-share-root\"")))
                    .andExpect(content().string(containsString("class=\"public-share-app\"")))
                    .andExpect(content().string(containsString("/react/assets/publicShare-")))
                    .andReturn().getResponse().getContentAsString();
            Matcher bootstrapScript = Pattern.compile(
                    "<script[^>]*id=\"public-share-bootstrap\"[^>]*>(.*?)</script>", Pattern.DOTALL).matcher(html);
            assertThat(bootstrapScript.find()).isTrue();
            JsonNode bootstrap = mapper.readTree(bootstrapScript.group(1));
            assertThat(bootstrap.size()).isEqualTo(3);
            assertThat(bootstrap.get("token").asText()).isEqualTo(link.token());
            assertThat(bootstrap.get("targetType").asText()).isEqualTo(link.type().name());
            assertThat(bootstrap.get("rootUrl").asText()).isEqualTo("/s/" + link.token());
            assertThat(html).doesNotContain(privatePrefix, "private text", "plain.txt", "_csrf",
                    "admin-app-root", "/react/assets/adminApp-", "/react/assets/shell-", "/api/v1/",
                    "/js/file-selection.js", "/react/assets/sharedImage-", "/react/assets/sharedComic-",
                    "/react/assets/fileTools-");
        }
        mockMvc.perform(get("/s/{token}/file", directoryLink.token()).param("item", "plain.txt"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("id=\"public-share-root\"")));
        mockMvc.perform(get("/s/{token}/file", fileLink.token()).param("item", "plain.txt"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/s/{token}", directoryLink.token()).param("path", "../outside"))
                .andExpect(status().isForbidden());
        String missingHost = mockMvc.perform(get("/s/{token}/file", directoryLink.token()).param("item", "missing.txt"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Shared content is unavailable.")))
                .andReturn().getResponse().getContentAsString();
        assertThat(missingHost).doesNotContain(privatePrefix, "missing.txt", ROOT.toString());
        shares.revoke(directoryLink.token());
        mockMvc.perform(get("/s/{token}", directoryLink.token())).andExpect(status().isNotFound());
    }

    @Test
    void successfulJsonViewsRecordOneAccessEachAndHtmlOrFailedViewsDoNotDuplicateIt() throws Exception {
        String directory = directory("activity");
        write(directory + "/plain.txt", "text");
        ShareLink link = share(directory, true);
        long before = accessCount(directory);
        mockMvc.perform(get("/s/{token}", link.token())).andExpect(status().isOk());
        mockMvc.perform(get("/s/{token}/file", link.token()).param("item", "plain.txt")).andExpect(status().isOk());
        assertThat(accessCount(directory)).isEqualTo(before);
        mockMvc.perform(get("/s/{token}/listing.json", link.token())).andExpect(status().isOk());
        assertThat(accessCount(directory)).isEqualTo(before + 1);
        mockMvc.perform(get("/s/{token}/detail.json", link.token()).param("item", "plain.txt"))
                .andExpect(status().isOk());
        assertThat(accessCount(directory)).isEqualTo(before + 2);
        mockMvc.perform(get("/s/{token}/preview", link.token()).param("item", "plain.txt")).andExpect(status().isOk());
        mockMvc.perform(get("/s/{token}/detail.json", link.token()).param("item", "missing.txt"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/s/{token}/listing.json", link.token()).param("path", "../outside"))
                .andExpect(status().isForbidden());
        assertThat(accessCount(directory)).isEqualTo(before + 2);
        var entries = activity.recentByMetadata("tokenFingerprint", tokens.fingerprint(link.token()), 100);
        assertThat(entries).hasSize(2);
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.type()).isEqualTo("SHARE_ACCESS");
            assertThat(entry.path()).isEqualTo(directory);
            assertThat(entry.metadata()).containsOnlyKeys("tokenFingerprint");
            assertThat(entry.toString()).doesNotContain(link.token());
        });
        assertThat(entries).anySatisfy(entry -> assertThat(entry.targetPath()).isEqualTo("plain.txt"));
        assertThat(entries).anySatisfy(entry -> assertThat(entry.targetPath()).isNull());
    }

    @Test
    void directFileAccessUsesTokenFingerprintAndDoesNotLogIgnoredQueryTargets() throws Exception {
        String directory = directory("file-activity");
        write(directory + "/plain.txt", "text");
        ShareLink link = share(directory + "/plain.txt", true);
        mockMvc.perform(get("/s/{token}", link.token())).andExpect(status().isOk());
        assertThat(accessCount(directory + "/plain.txt")).isZero();
        mockMvc.perform(get("/s/{token}/detail.json", link.token()).param("path", "../ignored").param("item", "other.txt"))
                .andExpect(status().isOk());
        var entries = activity.recentByMetadata("tokenFingerprint", tokens.fingerprint(link.token()), 100);
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.type()).isEqualTo("SHARE_ACCESS");
            assertThat(entry.path()).isEqualTo(directory + "/plain.txt");
            assertThat(entry.targetPath()).isNull();
            assertThat(entry.message()).isEqualTo("Accessed share link");
            assertThat(entry.toString()).doesNotContain(link.token(), "../ignored", "other.txt");
        });
    }

    private void unavailable(String token, String endpoint, int expectedStatus) throws Exception {
        mockMvc.perform(get("/s/{token}/" + endpoint, token).param("item", "plain.txt"))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.notification.message").value("Shared content is unavailable."));
    }

    private ShareLink share(String path, boolean preview) throws IOException {
        return shares.createForVaultPath(path, null, null, preview);
    }

    private String directory(String prefix) throws IOException {
        String name = prefix + "-" + System.nanoTime();
        Files.createDirectories(ROOT.resolve(name));
        return name;
    }

    private void write(String path, String content) throws IOException {
        Path file = ROOT.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private long accessCount(String directory) throws IOException {
        return activity.recentCurrentEntries(1000).stream()
                .filter(entry -> "SHARE_ACCESS".equals(entry.type()) && directory.equals(entry.path()))
                .count();
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("endervault-shared-browser-api-");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
