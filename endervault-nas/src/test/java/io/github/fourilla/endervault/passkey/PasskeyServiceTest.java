package io.github.fourilla.endervault.passkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.yubico.webauthn.data.ByteArray;
import io.github.fourilla.endervault.config.NasProperties;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpSession;

class PasskeyServiceTest {

    @TempDir
    Path root;

    private ObjectMapper objectMapper;
    private PasskeyRepository repository;
    private PasskeyService service;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        properties.getAdmin().setUsername("admin");
        properties.getPasskeys().setRpId("localhost");
        properties.getPasskeys().setAllowedOrigins(List.of("http://localhost:8080"));

        objectMapper = JsonMapper.builder().findAndAddModules().build();
        repository = new PasskeyRepository(objectMapper, properties);
        repository.initialize();
        service = new PasskeyService(properties, repository);
    }

    @Test
    void startsRegistrationWithBrowserCredentialOptions() throws Exception {
        String json = service.startRegistration(new MockHttpSession());
        JsonNode rootNode = objectMapper.readTree(json);

        assertThat(rootNode.has("publicKey")).isTrue();
        assertThat(rootNode.at("/publicKey/rp/id").asText()).isEqualTo("localhost");
        assertThat(rootNode.at("/publicKey/user/name").asText()).isEqualTo("admin");
        assertThat(rootNode.at("/publicKey/challenge").asText()).isNotBlank();
        assertThat(rootNode.at("/publicKey/authenticatorSelection/residentKey").asText()).isEqualTo("required");
        assertThat(rootNode.at("/publicKey/authenticatorSelection/userVerification").asText()).isEqualTo("required");
    }

    @Test
    void refusesLoginStartBeforeCredentialExists() {
        assertThatThrownBy(() -> service.startLogin(new MockHttpSession()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No passkey");
    }

    @Test
    void startsAssertionAfterCredentialExists() throws Exception {
        ByteArray userHandle = repository.getOrCreateUserHandle();
        repository.saveNewCredential(
                "Laptop",
                userHandle.getBase64Url(),
                new ByteArray(new byte[] {1, 2, 3}).getBase64Url(),
                new ByteArray(new byte[] {4, 5, 6}).getBase64Url(),
                1L,
                true,
                false,
                false,
                "platform",
                List.of("internal")
        );

        String json = service.startLogin(new MockHttpSession());
        JsonNode rootNode = objectMapper.readTree(json);

        assertThat(rootNode.has("publicKey")).isTrue();
        assertThat(rootNode.at("/publicKey/rpId").asText()).isEqualTo("localhost");
        assertThat(rootNode.at("/publicKey/challenge").asText()).isNotBlank();
        assertThat(rootNode.at("/publicKey/userVerification").asText()).isEqualTo("required");
    }
}
