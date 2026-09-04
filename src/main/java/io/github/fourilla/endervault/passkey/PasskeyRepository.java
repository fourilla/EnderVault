package io.github.fourilla.endervault.passkey;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.exception.Base64UrlException;
import io.github.fourilla.endervault.common.JsonRegistry;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

@Repository
public class PasskeyRepository implements CredentialRepository {

    private static final String STORE_FILE_NAME = "passkeys.json";
    private static final int USER_HANDLE_BYTES = 32;
    private static final TypeReference<PasskeyStore> PASSKEY_STORE = new TypeReference<>() {
    };

    private final JsonRegistry<PasskeyStore> registry;
    private final NasProperties nasProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasskeyRepository(ObjectMapper objectMapper, NasProperties nasProperties) {
        this.nasProperties = nasProperties;
        NasProperties.Storage storage = nasProperties.getStorage();
        this.registry = new JsonRegistry<>(
                objectMapper,
                storage.getRoot()
                        .toAbsolutePath()
                        .normalize()
                        .resolve(storage.getMetadataDirectory())
                        .resolve(STORE_FILE_NAME),
                PASSKEY_STORE,
                PasskeyStore::new,
                JsonRegistry.CorruptionPolicy.BACKUP_AND_THROW
        );
    }

    @PostConstruct
    public void initialize() throws IOException {
        registry.initialize();
    }

    public synchronized List<PasskeyCredential> list() {
        return readStore().getCredentials().stream()
                .sorted(Comparator.comparing(PasskeyCredential::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public synchronized boolean hasCredentials() {
        return !readStore().getCredentials().isEmpty();
    }

    public synchronized int count() {
        return readStore().getCredentials().size();
    }

    public synchronized ByteArray getOrCreateUserHandle() {
        PasskeyStore store = readStore();
        if (store.getUserHandle() == null || store.getUserHandle().isBlank()) {
            byte[] bytes = new byte[USER_HANDLE_BYTES];
            secureRandom.nextBytes(bytes);
            store.setUserHandle(new ByteArray(bytes).getBase64Url());
            writeStore(store);
        }
        return byteArray(store.getUserHandle());
    }

    public synchronized PasskeyCredential saveNewCredential(
            String label,
            String userHandle,
            String credentialId,
            String publicKeyCose,
            long signatureCount,
            boolean discoverable,
            boolean backupEligible,
            boolean backedUp,
            String authenticatorAttachment,
            List<String> transports
    ) {
        PasskeyStore store = readStore();
        List<PasskeyCredential> credentials = store.getCredentials().stream()
                .filter(credential -> !credentialId.equals(credential.credentialId()))
                .collect(Collectors.toCollection(java.util.ArrayList::new));
        PasskeyCredential credential = new PasskeyCredential(
                UUID.randomUUID().toString(),
                cleanLabel(label),
                adminUsername(),
                userHandle,
                credentialId,
                publicKeyCose,
                signatureCount,
                discoverable,
                backupEligible,
                backedUp,
                authenticatorAttachment,
                transports == null ? List.of() : List.copyOf(transports),
                Instant.now(),
                null
        );
        credentials.add(credential);
        store.setCredentials(credentials);
        writeStore(store);
        return credential;
    }

    public synchronized Optional<PasskeyCredential> delete(String id) {
        PasskeyStore store = readStore();
        List<PasskeyCredential> credentials = store.getCredentials();
        Optional<PasskeyCredential> removed = credentials.stream()
                .filter(credential -> credential.id().equals(id))
                .findFirst();
        if (removed.isEmpty()) {
            return Optional.empty();
        }

        store.setCredentials(credentials.stream()
                .filter(credential -> !credential.id().equals(id))
                .toList());
        writeStore(store);
        return removed;
    }

    public synchronized void updateUsage(ByteArray credentialId, long signatureCount, boolean backedUp) {
        PasskeyStore store = readStore();
        String credentialIdValue = credentialId.getBase64Url();
        store.setCredentials(store.getCredentials().stream()
                .map(credential -> credentialIdValue.equals(credential.credentialId())
                        ? credential.withUsage(signatureCount, backedUp)
                        : credential)
                .toList());
        writeStore(store);
    }

    @Override
    public synchronized Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        if (!adminUsername().equals(username)) {
            return Set.of();
        }
        return readStore().getCredentials().stream()
                .map(this::toDescriptor)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public synchronized Optional<ByteArray> getUserHandleForUsername(String username) {
        PasskeyStore store = readStore();
        if (!adminUsername().equals(username) || store.getUserHandle() == null || store.getUserHandle().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(byteArray(store.getUserHandle()));
    }

    @Override
    public synchronized Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
        String adminUsername = adminUsername();
        return userHandle != null
                && getUserHandleForUsername(adminUsername).filter(userHandle::equals).isPresent()
                ? Optional.of(adminUsername)
                : Optional.empty();
    }

    @Override
    public synchronized Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
        return readStore().getCredentials().stream()
                .filter(credential -> credential.credentialId().equals(credentialId.getBase64Url()))
                .filter(credential -> credential.userHandle().equals(userHandle.getBase64Url()))
                .findFirst()
                .map(this::toRegisteredCredential);
    }

    @Override
    public synchronized Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
        return readStore().getCredentials().stream()
                .filter(credential -> credential.credentialId().equals(credentialId.getBase64Url()))
                .map(this::toRegisteredCredential)
                .collect(Collectors.toUnmodifiableSet());
    }

    private PublicKeyCredentialDescriptor toDescriptor(PasskeyCredential credential) {
        PublicKeyCredentialDescriptor.PublicKeyCredentialDescriptorBuilder builder =
                PublicKeyCredentialDescriptor.builder()
                        .id(byteArray(credential.credentialId()));
        Set<AuthenticatorTransport> transports = toTransports(credential.transports());
        if (!transports.isEmpty()) {
            builder.transports(transports);
        }
        return builder.build();
    }

    private RegisteredCredential toRegisteredCredential(PasskeyCredential credential) {
        RegisteredCredential.RegisteredCredentialBuilder builder = RegisteredCredential.builder()
                .credentialId(byteArray(credential.credentialId()))
                .userHandle(byteArray(credential.userHandle()))
                .publicKeyCose(byteArray(credential.publicKeyCose()))
                .signatureCount(credential.signatureCount())
                .backupEligible(credential.backupEligible())
                .backupState(credential.backedUp());
        Set<AuthenticatorTransport> transports = toTransports(credential.transports());
        if (!transports.isEmpty()) {
            builder.transports(transports);
        }
        return builder.build();
    }

    private ByteArray byteArray(String value) {
        try {
            return ByteArray.fromBase64Url(value);
        } catch (Base64UrlException ex) {
            throw new StorageAccessException("Invalid passkey metadata.", ex);
        }
    }

    private Set<AuthenticatorTransport> toTransports(List<String> transports) {
        if (transports == null || transports.isEmpty()) {
            return Set.of();
        }
        return transports.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(AuthenticatorTransport::of)
                .collect(Collectors.toUnmodifiableSet());
    }

    private PasskeyStore readStore() {
        try {
            return registry.read();
        } catch (IOException ex) {
            throw new StorageAccessException("Failed to read passkey store.", ex);
        }
    }

    private void writeStore(PasskeyStore store) {
        try {
            registry.write(store);
        } catch (IOException ex) {
            throw new StorageAccessException("Failed to write passkey store.", ex);
        }
    }

    private String cleanLabel(String label) {
        if (label == null || label.isBlank()) {
            return "Passkey";
        }
        String trimmed = label.trim();
        return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
    }

    private String adminUsername() {
        return nasProperties.getAdmin().getUsername();
    }
}
