package io.github.fourilla.endervault.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

    @ParameterizedTest
    @EnumSource(JsonRegistry.CorruptionPolicy.class)
    void readIoFailureDoesNotBackupOrResetValidRegistry(JsonRegistry.CorruptionPolicy policy) throws Exception {
        Path registryFile = root.resolve("items.json");
        String original = "[{\"name\":\"keep\"}]";
        Files.writeString(registryFile, original);
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        IOException failure = new IOException("Storage temporarily unavailable");
        when(failingMapper.readValue(registryFile.toFile(), sampleListType))
                .thenThrow(JacksonIOException.construct(failure));
        JsonRegistry<List<SampleItem>> registry = new JsonRegistry<>(
                failingMapper, registryFile, sampleListType, List::of, policy);

        assertThatThrownBy(registry::read).isSameAs(failure);

        assertThat(Files.readString(registryFile)).isEqualTo(original);
        assertThat(backupFiles()).isEmpty();
        assertThat(tempFiles()).isEmpty();
    }

    @Test
    void writeIoFailurePreservesRegistryAndCleansTemporaryFile() throws Exception {
        Path registryFile = root.resolve("items.json");
        String original = "[{\"name\":\"keep\"}]";
        Files.writeString(registryFile, original);
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        ObjectWriter failingWriter = mock(ObjectWriter.class);
        IOException failure = new IOException("Disk write failed");
        when(failingMapper.writerWithDefaultPrettyPrinter()).thenReturn(failingWriter);
        doThrow(JacksonIOException.construct(failure)).when(failingWriter)
                .writeValue(org.mockito.ArgumentMatchers.any(java.io.File.class), org.mockito.ArgumentMatchers.any());
        JsonRegistry<List<SampleItem>> registry = new JsonRegistry<>(
                failingMapper, registryFile, sampleListType, List::of, JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW);

        assertThatThrownBy(() -> registry.write(List.of(new SampleItem("new"))))
                .isInstanceOf(IOException.class)
                .hasCause(failure);

        assertThat(Files.readString(registryFile)).isEqualTo(original);
        assertThat(tempFiles()).isEmpty();
    }

    @Test
    void serializationFailurePreservesRegistryAndIOExceptionContract() throws Exception {
        Path registryFile = root.resolve("items.json");
        Files.writeString(registryFile, "[]");
        JsonRegistry<List<UnreadableItem>> registry = new JsonRegistry<>(
                objectMapper, registryFile, new TypeReference<>() {}, List::of,
                JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW);

        assertThatThrownBy(() -> registry.write(List.of(new UnreadableItem("value"))))
                .isInstanceOf(IOException.class);

        assertThat(Files.readString(registryFile)).isEqualTo("[]");
        assertThat(tempFiles()).isEmpty();
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

    private record UnreadableItem(String name) {
        @Override
        public String name() {
            throw new IllegalStateException("Cannot serialize this value");
        }
    }
}
