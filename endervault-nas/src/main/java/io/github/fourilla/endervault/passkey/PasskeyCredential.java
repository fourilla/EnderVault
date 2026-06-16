package io.github.fourilla.endervault.passkey;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public record PasskeyCredential(
        String id,
        String label,
        String username,
        String userHandle,
        String credentialId,
        String publicKeyCose,
        long signatureCount,
        boolean discoverable,
        boolean backupEligible,
        boolean backedUp,
        String authenticatorAttachment,
        List<String> transports,
        Instant createdAt,
        Instant lastUsedAt
) {
    private static final DateTimeFormatter LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public String displayLabel() {
        return label == null || label.isBlank() ? "Unnamed passkey" : label;
    }

    public String createdLabel() {
        return createdAt == null ? "-" : LABEL_FORMATTER.format(createdAt);
    }

    public String lastUsedLabel() {
        return lastUsedAt == null ? "Never" : LABEL_FORMATTER.format(lastUsedAt);
    }

    public String shortCredentialId() {
        if (credentialId == null || credentialId.isBlank()) {
            return "-";
        }
        return credentialId.length() <= 18
                ? credentialId
                : credentialId.substring(0, 10) + "..." + credentialId.substring(credentialId.length() - 6);
    }

    public String transportLabel() {
        if (transports == null || transports.isEmpty()) {
            return "-";
        }
        return String.join(", ", transports);
    }

    public PasskeyCredential withUsage(long nextSignatureCount, boolean nextBackedUp) {
        return new PasskeyCredential(
                id,
                label,
                username,
                userHandle,
                credentialId,
                publicKeyCose,
                nextSignatureCount,
                discoverable,
                backupEligible,
                nextBackedUp,
                authenticatorAttachment,
                transports,
                createdAt,
                Instant.now()
        );
    }
}
