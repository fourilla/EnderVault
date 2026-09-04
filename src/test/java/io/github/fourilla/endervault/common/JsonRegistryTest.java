package io.github.fourilla.endervault.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonRegistryTest {

    @TempDir
    Path root;

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final TypeReference<List<SampleItem>> sampleListType = new TypeReference<>() {
    };

    @Test
    void initializesMissingRegistryWithDefaultValue() throws Exception {
        Path registryFile = root.resolve("items.json");
        JsonRegistry<List<SampleItem>> registry = resettableRegistry(registryFile);

        registry.initialize();

        assertThat(registryFile).exists();
        assertThat(registry.read()).isEmpty();
    }

    @Test
    void writesThroughTemporaryFileAndCleansStaleTemps() throws Exception {
        Path registryFile = root.resolve("items.json");
        Files.writeString(root.resolve("items.json.tmp"), "old");
        Files.writeString(root.resolve("items.json-leftover.tmp"), "old");
        JsonRegistry<List<SampleItem>> registry = resettableRegistry(registryFile);

        registry.write(List.of(new SampleItem("alpha")));

        assertThat(registry.read()).containsExactly(new SampleItem("alpha"));
        assertThat(tempFiles()).isEmpty();
    }

    @Test
    void backsUpCorruptRegistryAndResetsWhenConfigured() throws Exception {
        Path registryFile = root.resolve("items.json");
        Files.writeString(registryFile, "{not-json");
        JsonRegistry<List<SampleItem>> registry = resettableRegistry(registryFile);

        assertThat(registry.read()).isEmpty();

        assertThat(Files.readString(registryFile)).doesNotContain("not-json");
        assertThat(backupFiles()).hasSize(1);
    }

    @Test
    void backsUpCorruptRegistryAndThrowsWhenConfigured() throws Exception {
        Path registryFile = root.resolve("items.json");
        Files.writeString(registryFile, "{not-json");
        JsonRegistry<List<SampleItem>> registry = new JsonRegistry<>(
                objectMapper,
                registryFile,
                sampleListType,
                List::of,
                JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW
        );

        assertThatThrownBy(registry::read)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Invalid JSON registry");
        assertThat(backupFiles()).hasSize(1);
        assertThat(Files.readString(registryFile)).contains("not-json");
    }

    private JsonRegistry<List<SampleItem>> resettableRegistry(Path registryFile) {
        return new JsonRegistry<>(
                objectMapper,
                registryFile,
                sampleListType,
                List::of,
                JsonRegistry.CorruptionPolicy.BACKUP_AND_RESET
        );
    }

    private List<Path> tempFiles() throws IOException {
        try (Stream<Path> stream = Files.list(root)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .toList();
        }
    }

    private List<Path> backupFiles() throws IOException {
        try (Stream<Path> stream = Files.list(root)) {
            return stream
                    .filter(path -> path.getFileName().toString().contains(".corrupt-"))
                    .toList();
        }
    }

    private record SampleItem(String name) {
    }
}
