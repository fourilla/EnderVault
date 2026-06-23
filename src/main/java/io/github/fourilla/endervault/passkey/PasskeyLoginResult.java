package io.github.fourilla.endervault.passkey;

public record PasskeyLoginResult(
        String username,
        String credentialId,
        boolean userVerified,
        boolean backedUp
) {
}
