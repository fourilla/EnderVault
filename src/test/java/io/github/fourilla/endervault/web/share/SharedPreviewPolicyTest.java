package io.github.fourilla.endervault.web.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.FileToolCapability;
import io.github.fourilla.endervault.filetool.FileToolDescriptor;
import io.github.fourilla.endervault.filetool.FileToolType;
import java.nio.file.NoSuchFileException;
import org.junit.jupiter.api.Test;

class SharedPreviewPolicyTest {

    @Test
    void combinesTheGlobalSettingWithTheExplicitFileCapability() throws Exception {
        NasProperties properties = new NasProperties();
        SharedPreviewPolicy policy = new SharedPreviewPolicy(properties);
        FileToolDescriptor image = FileToolDescriptor.of(
                FileToolType.IMAGE,
                FileToolCapability.INLINE_PREVIEW,
                FileToolCapability.SHARED_PREVIEW
        );
        FileToolDescriptor pdf = FileToolDescriptor.of(
                FileToolType.PDF,
                FileToolCapability.INLINE_PREVIEW
        );

        assertThat(policy.isEnabled(image)).isTrue();
        assertThat(policy.isEnabled(pdf)).isFalse();
        assertThatThrownBy(() -> policy.requireEnabled(pdf))
                .isInstanceOf(NoSuchFileException.class)
                .hasMessageContaining("Shared preview is unavailable");

        properties.getShare().setDefaultPreviewEnabled(false);
        assertThat(policy.isEnabled(image)).isFalse();
        assertThatThrownBy(() -> policy.requireEnabled(image))
                .isInstanceOf(NoSuchFileException.class);
    }
}
