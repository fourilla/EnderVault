package io.github.fourilla.endervault.passkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.yubico.webauthn.data.ByteArray;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PasskeyRepositoryTest {

    @TempDir
    Path root;

    private PasskeyRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        properties.getAdmin().setUsername("admin");

        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        repository = new PasskeyRepository(objectMapper, properties);
        repository.initialize();
    }

    @Test
    void createsStableUserHandle() {
        ByteArray first = repository.getOrCreateUserHandle();
        ByteArray second = repository.getOrCreateUserHandle();

        assertThat(first).isEqualTo(second);
        assertThat(root.resolve(".endervault").resolve("passkeys.json")).exists();
    }

    @Test
    void storesAndLooksUpCredential() {
        ByteArray userHandle = repository.getOrCreateUserHandle();
        ByteArray credentialId = new ByteArray(new byte[] {1, 2, 3});
        ByteArray publicKeyCose = new ByteArray(new byte[] {4, 5, 6});

        PasskeyCredential saved = repository.saveNewCredential(
                "Laptop",
                userHandle.getBase64Url(),
                credentialId.getBase64Url(),
                publicKeyCose.getBase64Url(),
                7L,
                true,
                true,
                false,
                "platform",
                List.of("internal")
        );

        assertThat(repository.list()).extracting(PasskeyCredential::displayLabel).containsExactly("Laptop");
        assertThat(repository.getCredentialIdsForUsername("admin"))
                .extracting(descriptor -> descriptor.getId().getBase64Url())
                .containsExactly(credentialId.getBase64Url());
        assertThat(repository.getUserHandleForUsername("admin")).contains(userHandle);
        assertThat(repository.getUsernameForUserHandle(userHandle)).contains("admin");
        assertThat(repository.lookup(credentialId, userHandle)).isPresent();
        assertThat(repository.lookupAll(credentialId)).hasSize(1);

        repository.updateUsage(credentialId, 9L, true);
        assertThat(repository.list().getFirst().signatureCount()).isEqualTo(9L);
        assertThat(repository.list().getFirst().backedUp()).isTrue();
        assertThat(repository.list().getFirst().lastUsedAt()).isNotNull();

        assertThat(repository.delete(saved.id()))
                .isPresent()
                .get()
                .extracting(PasskeyCredential::id)
                .isEqualTo(saved.id());
        assertThat(repository.hasCredentials()).isFalse();
    }

    @Test
    void corruptStoreIsBackedUpAndRejected() throws Exception {
        Path storeFile = root.resolve(".endervault").resolve("passkeys.json");
        Files.writeString(storeFile, "{not-json");

        assertThatThrownBy(repository::hasCredentials)
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("Failed to read passkey store");
        assertThat(corruptBackups()).hasSize(1);
        assertThat(Files.readString(storeFile)).contains("not-json");
    }

    private List<Path> corruptBackups() throws IOException {
        try (Stream<Path> stream = Files.list(root.resolve(".endervault"))) {
            return stream
                    .filter(path -> path.getFileName().toString().contains("passkeys.json.corrupt-"))
                    .toList();
        }
    }
}
