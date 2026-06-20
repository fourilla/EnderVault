package io.github.fourilla.endervault.metadata;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public record MetadataIssue(
        MetadataArea area,
        MetadataIssueSeverity severity,
        MetadataIssueAction action,
        String subject,
        String title,
        String detail,
        String recommendation
) {
    private static final String TOKEN_SEPARATOR = "\u001f";

    public boolean repairable() {
        return action.repairable();
    }

    public String token() {
        String raw = area.name() + TOKEN_SEPARATOR + action.name() + TOKEN_SEPARATOR + subject;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static RepairRequest repairRequest(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split(TOKEN_SEPARATOR, 3);
            if (parts.length != 3) {
                throw new IllegalArgumentException("Invalid repair token.");
            }
            return new RepairRequest(
                    MetadataArea.valueOf(parts[0]),
                    MetadataIssueAction.valueOf(parts[1]),
                    parts[2]
            );
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid repair token.", ex);
        }
    }

    record RepairRequest(MetadataArea area, MetadataIssueAction action, String subject) {
    }
}
