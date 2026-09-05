package io.github.fourilla.endervault;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class EnderVaultNasApplicationTests {

    private static final Path ROOT = createTempRoot();

    @Autowired
    private WebProperties webProperties;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nas.storage.root", ROOT::toString);
    }

    @Test
    void contextLoads() {
    }

    @Test
    void defaultWhitelabelErrorPageIsDisabled() {
        assertThat(webProperties.getError().getWhitelabel().isEnabled()).isFalse();
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("endervault-context-");
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create test storage root.", ex);
        }
    }
}
