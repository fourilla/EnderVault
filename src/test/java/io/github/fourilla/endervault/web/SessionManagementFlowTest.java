package io.github.fourilla.endervault.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.fourilla.endervault.session.SessionManagementService;
import io.github.fourilla.endervault.session.SessionView;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "nas.admin.username=admin",
        "nas.admin.password={noop}secret",
        "nas.notifications.telegram.enabled=false",
        "nas.security.max-concurrent-sessions=1",
        "nas.security.session-idle-timeout-minutes=7"
})
@AutoConfigureMockMvc
class SessionManagementFlowTest {

    private static final Path ROOT = createTempRoot();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    SessionManagementService sessionManagementService;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nas.storage.root", ROOT::toString);
    }

    @Test
    void formLoginAppliesTimeoutAndExpiresTheOldestConcurrentSession() throws Exception {
        MockHttpSession first = login("First Browser");

        assertThat(first.getMaxInactiveInterval()).isEqualTo(420);

        MockHttpSession second = login("Second Browser");

        assertThat(sessionManagementService.activeCount()).isEqualTo(1);
        assertThat(sessionManagementService.listActive(second.getId()))
                .singleElement()
                .satisfies(session -> {
                    assertThat(session.current()).isTrue();
                    assertThat(session.managementId()).isNotEqualTo(second.getId());
                });

        mockMvc.perform(get("/files").session(first))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?expired"));

        mockMvc.perform(get("/admin/sessions").session(second))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"admin-app-root\"")))
                .andExpect(content().string(not(containsString("/admin/sessions/revoke"))));

        mockMvc.perform(get("/api/v1/sessions").session(second))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sessions[0].current").value(true))
                .andExpect(jsonPath("$.sessions[0].userAgent").value("Second Browser"))
                .andExpect(jsonPath("$.sessions[0].lastActiveLabel").isString())
                .andExpect(jsonPath("$.sessions[0].managementId").isString())
                .andExpect(jsonPath("$.sessions[0].sessionId").doesNotExist());

        mockMvc.perform(get("/admin/settings").param("section", "sessions").session(second))
                .andExpect(status().isOk());
    }

    @Test
    void currentSessionCanBeRevokedThroughTheManagementEndpoint() throws Exception {
        MockHttpSession session = login("Session To Revoke");
        SessionView current = sessionManagementService.listActive(session.getId()).getFirst();

        mockMvc.perform(post("/api/v1/sessions/revoke")
                        .session(session)
                        .with(csrf())
                        .accept(MediaType.APPLICATION_JSON)
                        .param("managementId", current.managementId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirectUrl").value("/login?session-revoked"));

        assertThat(sessionManagementService.activeCount()).isZero();
    }

    @Test
    void removedSessionMutationEndpointIsNotAvailable() throws Exception {
        MockHttpSession session = login("Legacy Session Endpoint");
        SessionView current = sessionManagementService.listActive(session.getId()).getFirst();

        mockMvc.perform(post("/admin/sessions/revoke")
                        .session(session)
                        .with(csrf())
                        .param("managementId", current.managementId()))
                .andExpect(status().isNotFound());
    }

    private MockHttpSession login(String userAgent) throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .with(csrf())
                        .header("User-Agent", userAgent)
                        .param("username", "admin")
                        .param("password", "secret"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files"))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("endervault-session-management-");
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create test storage root.", ex);
        }
    }
}
