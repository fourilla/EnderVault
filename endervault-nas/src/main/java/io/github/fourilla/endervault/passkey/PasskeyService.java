package io.github.fourilla.endervault.passkey;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import io.github.fourilla.endervault.config.NasProperties;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class PasskeyService {

    private static final String REGISTRATION_REQUEST_SESSION_KEY = "endervault.passkey.registrationRequest";
    private static final String ASSERTION_REQUEST_SESSION_KEY = "endervault.passkey.assertionRequest";

    private final NasProperties nasProperties;
    private final PasskeyRepository passkeyRepository;
    private final RelyingParty relyingParty;

    public PasskeyService(NasProperties nasProperties, PasskeyRepository passkeyRepository) {
        this.nasProperties = nasProperties;
        this.passkeyRepository = passkeyRepository;
        NasProperties.Passkeys passkeys = nasProperties.getPasskeys();
        this.relyingParty = RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder()
                        .id(passkeys.getRpId())
                        .name(passkeys.getRpName())
                        .build())
                .credentialRepository(passkeyRepository)
                .origins(cleanOrigins(passkeys.getAllowedOrigins()))
                .allowUntrustedAttestation(true)
                .build();
    }

    public boolean isEnabled() {
        return nasProperties.getPasskeys().isEnabled();
    }

    public boolean isPasswordLoginEnabled() {
        return nasProperties.getPasskeys().isPasswordLoginEnabled();
    }

    public String rpId() {
        return nasProperties.getPasskeys().getRpId();
    }

    public Set<String> allowedOrigins() {
        return cleanOrigins(nasProperties.getPasskeys().getAllowedOrigins());
    }

    public List<PasskeyCredential> listCredentials() {
        return passkeyRepository.list();
    }

    public boolean hasCredentials() {
        return passkeyRepository.hasCredentials();
    }

    public String startRegistration(HttpSession session) {
        ensureEnabled();
        ByteArray userHandle = passkeyRepository.getOrCreateUserHandle();
        PublicKeyCredentialCreationOptions request = relyingParty.startRegistration(
                StartRegistrationOptions.builder()
                        .user(UserIdentity.builder()
                                .name(nasProperties.getAdmin().getUsername())
                                .displayName("EnderVault Admin")
                                .id(userHandle)
                                .build())
                        .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                                .residentKey(ResidentKeyRequirement.REQUIRED)
                                .userVerification(UserVerificationRequirement.REQUIRED)
                                .build())
                        .build()
        );
        try {
            session.setAttribute(REGISTRATION_REQUEST_SESSION_KEY, request.toJson());
            return request.toCredentialsCreateJson();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to create passkey registration request.", ex);
        }
    }

    public PasskeyCredential finishRegistration(HttpSession session, String credentialJson, String label)
            throws IOException, RegistrationFailedException {
        ensureEnabled();
        String requestJson = takeSessionAttribute(session, REGISTRATION_REQUEST_SESSION_KEY);
        PublicKeyCredentialCreationOptions request = PublicKeyCredentialCreationOptions.fromJson(requestJson);
        PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> credential =
                PublicKeyCredential.parseRegistrationResponseJson(credentialJson);
        RegistrationResult result = relyingParty.finishRegistration(FinishRegistrationOptions.builder()
                .request(request)
                .response(credential)
                .build());

        return passkeyRepository.saveNewCredential(
                label,
                request.getUser().getId().getBase64Url(),
                result.getKeyId().getId().getBase64Url(),
                result.getPublicKeyCose().getBase64Url(),
                result.getSignatureCount(),
                result.isDiscoverable().orElse(false),
                result.isBackupEligible(),
                result.isBackedUp(),
                result.getAuthenticatorAttachment().map(Object::toString).orElse(""),
                credential.getResponse().getTransports().stream()
                        .map(transport -> transport.getId())
                        .toList()
        );
    }

    public String startLogin(HttpSession session) {
        ensureEnabled();
        if (!hasCredentials()) {
            throw new IllegalStateException("No passkey is registered.");
        }
        AssertionRequest request = relyingParty.startAssertion(StartAssertionOptions.builder()
                .userVerification(UserVerificationRequirement.REQUIRED)
                .build());
        try {
            session.setAttribute(ASSERTION_REQUEST_SESSION_KEY, request.toJson());
            return request.toCredentialsGetJson();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to create passkey login request.", ex);
        }
    }

    public PasskeyLoginResult finishLogin(HttpSession session, String credentialJson)
            throws IOException, AssertionFailedException {
        ensureEnabled();
        String requestJson = takeSessionAttribute(session, ASSERTION_REQUEST_SESSION_KEY);
        AssertionRequest request = AssertionRequest.fromJson(requestJson);
        PublicKeyCredential<com.yubico.webauthn.data.AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> credential =
                PublicKeyCredential.parseAssertionResponseJson(credentialJson);
        AssertionResult result = relyingParty.finishAssertion(FinishAssertionOptions.builder()
                .request(request)
                .response(credential)
                .build());
        if (!result.isSuccess()) {
            throw new AssertionFailedException("Passkey assertion was not successful.");
        }
        passkeyRepository.updateUsage(result.getCredentialId(), result.getSignatureCount(), result.isBackedUp());
        return new PasskeyLoginResult(
                result.getUsername(),
                result.getCredentialId().getBase64Url(),
                result.isUserVerified(),
                result.isBackedUp()
        );
    }

    public PasskeyCredential deleteCredential(String id) {
        ensureEnabled();
        return passkeyRepository.delete(id)
                .orElseThrow(() -> new IllegalArgumentException("Passkey not found."));
    }

    private void ensureEnabled() {
        if (!isEnabled()) {
            throw new IllegalStateException("Passkeys are disabled.");
        }
    }

    private String takeSessionAttribute(HttpSession session, String key) {
        Object value = session.getAttribute(key);
        session.removeAttribute(key);
        if (!(value instanceof String requestJson) || requestJson.isBlank()) {
            throw new IllegalStateException("Passkey request expired. Try again.");
        }
        return requestJson;
    }

    private Set<String> cleanOrigins(List<String> origins) {
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        if (origins != null) {
            origins.stream()
                    .filter(origin -> origin != null && !origin.isBlank())
                    .map(String::trim)
                    .forEach(cleaned::add);
        }
        if (cleaned.isEmpty()) {
            cleaned.add("http://localhost:8080");
        }
        return Set.copyOf(cleaned);
    }
}
