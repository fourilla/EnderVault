package io.github.fourilla.endervault.web.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.fourilla.endervault.storage.ConflictPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class FileConflictPoliciesTest {

    @Test
    void detectsAskPolicyOnlyForJsonRequests() {
        HttpServletRequest htmlRequest = mock(HttpServletRequest.class);
        HttpServletRequest jsonRequest = mock(HttpServletRequest.class);
        when(jsonRequest.getHeader(HttpHeaders.ACCEPT)).thenReturn(MediaType.APPLICATION_JSON_VALUE);

        assertThat(FileConflictPolicies.asks(" ask ")).isTrue();
        assertThat(FileConflictPolicies.asksForJson("ask", htmlRequest)).isFalse();
        assertThat(FileConflictPolicies.asksForJson("ask", jsonRequest)).isTrue();
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
