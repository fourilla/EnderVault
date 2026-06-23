package io.github.fourilla.endervault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class EnderVaultNasApplicationTests {

    private static final Path ROOT = createTempRoot();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nas.storage.root", ROOT::toString);
    }

    @Test
    void contextLoads() {
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("endervault-context-");
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create test storage root.", ex);
        }
    }
}
