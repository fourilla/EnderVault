package io.github.fourilla.endervault.publiclink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService.TokenPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicLinkTokenServiceTest {

    private final PublicLinkTokenService tokenService = new PublicLinkTokenService();
    private final TokenPolicy policy = new TokenPolicy("share", true, 12, 64, 24);

    @Test
    void generatesUrlSafeRandomTokenWhenCustomTokenIsBlank() {
        String token = tokenService.issue("", List.of(), policy);

        assertThat(token).hasSize(32).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void acceptsValidCustomToken() {
        assertThat(tokenService.issue("family_upload", List.of(), policy)).isEqualTo("family_upload");
    }

    @Test
    void rejectsShortUnsafeAndDuplicateCustomTokens() {
        assertThatThrownBy(() -> tokenService.issue("short", List.of(), policy))
                .isInstanceOf(StorageAccessException.class);
        assertThatThrownBy(() -> tokenService.issue("unsafe/token", List.of(), policy))
                .isInstanceOf(StorageAccessException.class);
        assertThatThrownBy(() -> tokenService.issue("family_upload", List.of("family_upload"), policy))
                .isInstanceOf(StorageAccessException.class);
    }

    @Test
    void producesStableNonReversibleFingerprint() {
        assertThat(tokenService.fingerprint("family_upload"))
                .isEqualTo(tokenService.fingerprint("family_upload"))
                .hasSize(12)
                .doesNotContain("family_upload");
    }
}
