package io.github.fourilla.endervault.web.auth;

import tools.jackson.databind.JsonNode;

public record PasskeyCredentialPayload(
        String label,
        JsonNode credential
) {
}
