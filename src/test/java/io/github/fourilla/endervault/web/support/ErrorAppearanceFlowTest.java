package io.github.fourilla.endervault.web.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.fourilla.endervault.config.AppearanceProperties;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.share.ShareLink;
import io.github.fourilla.endervault.share.ShareLinkService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
        "nas.share.enabled=true",
        "nas.share.directory-share-enabled=true",
        "nas.appearance.accent=#123456",
        "nas.appearance.background=#234567",
        "nas.appearance.button=#345678",
        "nas.appearance.control-size=extra-large"
})
@AutoConfigureMockMvc
@WithAnonymousUser
class ErrorAppearanceFlowTest {

    private static final Path ROOT = temporaryRoot();
    @Autowired MockMvc mockMvc;
    @Autowired ShareLinkService shares;
    @Autowired NasProperties properties;
    @Autowired AppearanceModelAdvice appearance;
    @Autowired FrontendAssetModelAdvice frontendAssets;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nas.storage.root", ROOT::toString);
    }

    @Test
    void anonymousInvalidShareRendersTheSameAppearanceAndStylesAsLogin() throws Exception {
        String login = mockMvc.perform(get("/login").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertStyled(login);
        String html = unavailableShare("missing-share-token");
        assertThat(html).contains("<h1>Not found</h1>", "Shared content is unavailable.");
        assertThat(html).doesNotContain(ROOT.toString(), "missing-share-token");
    }

    @Test
    void anonymousRevokedAndExpiredSharesStillRenderCurrentAppearance() throws Exception {
        String path = file("unavailable.txt");
        ShareLink revoked = shares.createForVaultPath(path, null);
        shares.revoke(revoked.token());
        ShareLink expired = shares.createForVaultPath(path, Instant.now().minusSeconds(60));
        unavailableShare(revoked.token());
        unavailableShare(expired.token());
    }

    @Test
    void anonymousDeniedSharePathRendersThemedForbiddenPageWithoutPrivatePath() throws Exception {
        String directory = directory("published");
        ShareLink link = shares.createForVaultPath(directory, null);
        String html = mockMvc.perform(get("/s/{token}", link.token())
                        .param("path", "../private-directory").accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden()).andExpect(view().name("error"))
                .andReturn().getResponse().getContentAsString();
        assertStyled(html);
        assertThat(html).contains("<h1>Access denied</h1>", "Shared content is unavailable.")
                .doesNotContain(ROOT.toString(), "private-directory", directory);
    }

    @Test
    void exceptionModelUsesUpdatedRuntimeAppearance() throws Exception {
        AppearanceProperties previous = properties.getAppearance();
        AppearanceProperties updated = new AppearanceProperties("#ABCDEF", previous.accentStrong(),
                previous.button(), previous.buttonHover(), previous.buttonText(), previous.background(),
                previous.panel(), previous.panelElevated(), previous.panelMuted(), previous.border(),
                previous.text(), previous.mutedText(), "extra-small", previous.cardSize());
        properties.setAppearance(updated);
        try {
            String html = unavailableShare("another-missing-token");
            assertThat(html).contains("--accent: #ABCDEF;", "--action-icon-size: 26px;");
            String generic = mockMvc.perform(errorDispatch(500).accept(MediaType.TEXT_HTML))
                    .andExpect(status().isInternalServerError())
                    .andReturn().getResponse().getContentAsString();
            assertStyled(generic);
        } finally {
            properties.setAppearance(previous);
        }
    }

    @Test
    void genericAnonymousErrorDispatchRenders404And500WithoutLeakingContainerDetails() throws Exception {
        for (int code : new int[] {404, 500}) {
            MvcResult result = mockMvc.perform(errorDispatch(code).accept(MediaType.TEXT_HTML))
                    .andExpect(status().is(code)).andExpect(view().name("error"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                    .andReturn();
            String html = result.getResponse().getContentAsString();
            assertStyled(html);
            assertThat(html).doesNotContain("container-secret", ROOT.toString(), "IllegalStateException", "Whitelabel");
            assertThat(result.getModelAndView().getModel()).doesNotContainKeys("exception", "trace", "path");
            assertThat(html).contains(code == 404 ? "<h1>Not Found</h1>" : "<h1>Internal Server Error</h1>");
        }
    }

    @Test
    void genericErrorDispatchRendersUnderAContextPath() throws Exception {
        String html = mockMvc.perform(get("/vault/error").contextPath("/vault").servletPath("/error")
                        .with(errorDispatcher())
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertStyled(html);
        assertThat(html).contains("href=\"/vault/\"");
    }

    @Test
    void genericErrorJsonKeepsBasicErrorControllerContract() throws Exception {
        mockMvc.perform(errorDispatch(500).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/s/unresolved"))
                .andExpect(jsonPath("$.appearanceStylesheet").doesNotExist())
                .andExpect(jsonPath("$.stylesFrontend").doesNotExist())
                .andExpect(jsonPath("$.notification").doesNotExist());
    }

    @Test
    void sharedExceptionJsonKeepsActionResponseContract() throws Exception {
        for (String path : new String[] {"/s/missing-token", "/s/missing-token/listing.json"}) {
            mockMvc.perform(get(path).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.notification.message").value("Shared content is unavailable."))
                    .andExpect(jsonPath("$.appearanceStylesheet").doesNotExist())
                    .andExpect(jsonPath("$.stylesFrontend").doesNotExist());
        }
    }

    @Test
    void normalErrorAdminFilesAndApiRequestsRemainProtected() throws Exception {
        for (String path : new String[] {"/error", "/admin/settings", "/files", "/api/v1/settings"}) {
            mockMvc.perform(get(path).accept(MediaType.TEXT_HTML))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(header().string("Location", endsWith("/login")));
        }
        for (String path : new String[] {"/admin/settings", "/files", "/api/v1/settings"}) {
            mockMvc.perform(get(path).with(user("reader").roles("USER")).accept(MediaType.TEXT_HTML))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void errorDispatcherDoesNotPermitDirectAdminFilesOrApiAccess() throws Exception {
        for (String path : new String[] {"/admin/settings", "/files", "/api/v1/settings"}) {
            mockMvc.perform(get(path).with(errorDispatcher()).accept(MediaType.TEXT_HTML))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(header().string("Location", endsWith("/login")));
        }
    }

    private String unavailableShare(String token) throws Exception {
        String html = mockMvc.perform(get("/s/{token}", token).accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound()).andExpect(view().name("error"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn().getResponse().getContentAsString();
        assertStyled(html);
        return html;
    }

    private void assertStyled(String html) {
        assertThat(html).contains("id=\"endervault-appearance\"", appearance.appearanceStylesheet());
        var styles = frontendAssets.stylesFrontend();
        if (styles.available()) {
            for (String stylesheet : styles.styles()) {
                assertThat(html).contains(stylesheet);
            }
        } else {
            assertThat(html).contains("/css/app.css");
        }
    }

    private MockHttpServletRequestBuilder errorDispatch(int code) {
        return get("/error").with(errorDispatcher())
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, code)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/s/unresolved")
                .requestAttr(RequestDispatcher.ERROR_MESSAGE, "container-secret " + ROOT)
                .requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("container-secret " + ROOT));
    }

    private static RequestPostProcessor errorDispatcher() {
        return request -> {
            request.setDispatcherType(DispatcherType.ERROR);
            return request;
        };
    }

    private String file(String suffix) throws IOException {
        String path = directory("files") + "/" + suffix;
        Files.writeString(ROOT.resolve(path), "shared text");
        return path;
    }

    private String directory(String suffix) throws IOException {
        String path = "appearance-" + System.nanoTime() + "-" + suffix;
        Files.createDirectories(ROOT.resolve(path));
        return path;
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("endervault-error-appearance-");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
