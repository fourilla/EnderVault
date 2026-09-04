package io.github.fourilla.endervault.web.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.filetool.FileToolCapability;
import io.github.fourilla.endervault.filetool.FileToolDescriptor;
import io.github.fourilla.endervault.filetool.FileToolType;
import io.github.fourilla.endervault.share.ShareLink;
import io.github.fourilla.endervault.share.ShareTargetType;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SharedPreviewPolicyTest {

    @Test
    void combinesTheLinkPolicyWithTheExplicitFileCapability() throws Exception {
        SharedPreviewPolicy policy = new SharedPreviewPolicy();
        ShareLink enabledLink = link(true);
        ShareLink disabledLink = link(false);
        FileToolDescriptor image = FileToolDescriptor.of(
                FileToolType.IMAGE,
                FileToolCapability.INLINE_PREVIEW,
                FileToolCapability.SHARED_PREVIEW
        );
        FileToolDescriptor pdf = FileToolDescriptor.of(
                FileToolType.PDF,
                FileToolCapability.INLINE_PREVIEW
        );

        assertThat(policy.isEnabled(enabledLink, image)).isTrue();
        assertThat(policy.isEnabled(enabledLink, pdf)).isFalse();
        assertThat(policy.isEnabled(disabledLink, image)).isFalse();
        assertThatThrownBy(() -> policy.requireEnabled(enabledLink, pdf))
                .isInstanceOf(NoSuchFileException.class)
                .hasMessageContaining("Shared preview is unavailable");
        assertThatThrownBy(() -> policy.requireEnabled(disabledLink, image))
                .isInstanceOf(NoSuchFileException.class);
    }

    private ShareLink link(boolean previewEnabled) {
        return new ShareLink(
                "preview-policy-token",
                "image.png",
                ShareTargetType.FILE,
                Instant.parse("2026-01-01T00:00:00Z"),
                null,
                true,
                previewEnabled
        );
    }
}
