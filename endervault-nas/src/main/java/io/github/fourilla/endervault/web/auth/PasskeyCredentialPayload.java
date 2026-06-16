package io.github.fourilla.endervault.web.auth;

import com.fasterxml.jackson.databind.JsonNode;

public record PasskeyCredentialPayload(
        String label,
        JsonNode credential
) {
}
