package io.github.fourilla.endervault.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.fourilla.endervault.activity.ActivityLogEntry;
import io.github.fourilla.endervault.activity.ActivityLogService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "nas.admin.username=admin",
        "nas.admin.password={noop}secret",
        "nas.notifications.telegram.enabled=false"
})
@AutoConfigureMockMvc
class LoginActivityLogTest {

    private static final Path ROOT = createTempRoot();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ActivityLogService activityLogService;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nas.storage.root", ROOT::toString);
    }

    @Test
    void successfulLoginWritesActivityLog() throws Exception {
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .header("X-Forwarded-For", "203.0.113.20")
                        .param("username", "admin")
                        .param("password", "secret"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files"));

        List<ActivityLogEntry> entries = activityLogService.recentCurrentEntries(20);

        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.type()).isEqualTo("LOGIN_SUCCESS");
            assertThat(entry.success()).isTrue();
            assertThat(entry.actor()).isEqualTo("admin");
            assertThat(entry.ip()).isEqualTo("203.0.113.20");
            assertThat(entry.metadataView()).containsEntry("username", "admin");
        });
    }

    @Test
    void failedLoginWritesActivityLog() throws Exception {
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .header("X-Forwarded-For", "203.0.113.21")
                        .param("username", "admin")
                        .param("password", "wrong"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));

        List<ActivityLogEntry> entries = activityLogService.recentCurrentEntries(20);

        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.type()).isEqualTo("LOGIN_FAILURE");
            assertThat(entry.success()).isFalse();
            assertThat(entry.actor()).isEqualTo("admin");
            assertThat(entry.ip()).isEqualTo("203.0.113.21");
            assertThat(entry.metadataView())
                    .containsEntry("username", "admin")
                    .containsEntry("reason", "bad credentials");
        });
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("endervault-login-log-");
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create test storage root.", ex);
        }
    }
}
