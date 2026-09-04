package io.github.fourilla.endervault.web.support;

import static org.assertj.core.api.Assertions.assertThat;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import org.junit.jupiter.api.Test;

class FileConflictPoliciesTest {

    @Test
    void detectsAskPolicy() {
        assertThat(FileConflictPolicies.asks(" ask ")).isTrue();
        assertThat(FileConflictPolicies.asks("cancel")).isFalse();
    }

    @Test
    void treatsDefaultCancelAsCancel() {
        assertThat(FileConflictPolicies.cancels("cancel", ConflictPolicy.RENAME)).isTrue();
        assertThat(FileConflictPolicies.cancels("default", ConflictPolicy.CANCEL)).isTrue();
        assertThat(FileConflictPolicies.cancels("default", ConflictPolicy.RENAME)).isFalse();
    }

    @Test
    void resolvesMutationAndTransferAskPoliciesDifferently() {
        assertThat(FileConflictPolicies.mutationPolicy("ask", ConflictPolicy.RENAME))
                .isEqualTo(ConflictPolicy.CANCEL);
        assertThat(FileConflictPolicies.transferPolicy("ask", ConflictPolicy.RENAME))
                .isEqualTo(ConflictPolicy.RENAME);
    }
}
