package io.github.fourilla.endervault.publiclink;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class PublicLinkTokenService {

    private static final int FINGERPRINT_HEX_LENGTH = 12;

    private final SecureRandom secureRandom = new SecureRandom();

    public String issue(String customToken, Collection<String> existingTokens, TokenPolicy policy) {
        Objects.requireNonNull(existingTokens, "existingTokens");
        Objects.requireNonNull(policy, "policy");

        if (customToken == null || customToken.isBlank()) {
            return randomToken(existingTokens, policy.randomTokenBytes());
        }
        if (!policy.customTokenEnabled()) {
            throw new StorageAccessException("Custom " + policy.label() + " tokens are disabled.");
        }

        String token = customToken.trim();
        int minLength = Math.max(1, policy.customTokenMinLength());
        int maxLength = Math.max(minLength, policy.customTokenMaxLength());
        if (token.length() < minLength || token.length() > maxLength || !hasSafeCharacters(token)) {
            throw new StorageAccessException(
                    "%s token must be %d-%d characters using letters, numbers, '-' or '_'."
                            .formatted(capitalize(policy.label()), minLength, maxLength)
            );
        }
        if (existingTokens.contains(token)) {
            throw new StorageAccessException(capitalize(policy.label()) + " token already exists.");
        }
        return token;
    }

    public String fingerprint(String token) {
        Objects.requireNonNull(token, "token");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, FINGERPRINT_HEX_LENGTH / 2);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    private String randomToken(Collection<String> existingTokens, int requestedBytes) {
        String token;
        do {
            byte[] bytes = new byte[Math.max(8, requestedBytes)];
            secureRandom.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (existingTokens.contains(token));
        return token;
    }

    private boolean hasSafeCharacters(String token) {
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            boolean valid = (ch >= 'A' && ch <= 'Z')
                    || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '-'
                    || ch == '_';
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "Link";
        }
        String trimmed = value.trim();
        return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
    }

    public record TokenPolicy(
            String label,
            boolean customTokenEnabled,
            int customTokenMinLength,
            int customTokenMaxLength,
            int randomTokenBytes
    ) {
    }
}
