package io.github.fourilla.endervault.filerequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.auth.ClientIpResolver;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.upload.ResumableUploadRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class FileRequestPublicAccessPolicyTest {

    @Test
    void limitsNewAdmissionsPerRequestAndResolvedClientIp() {
        NasProperties properties = new NasProperties();
        properties.getFileRequest().setRateLimitMaxAdmissions(2);
        properties.getFileRequest().setRateLimitWindowSeconds(60);
        FileRequestPublicAccessPolicy policy = new FileRequestPublicAccessPolicy(
                new ClientIpResolver(properties), properties
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");

        policy.requireNewUploadAdmission("request-1", request);
        policy.requireNewUploadAdmission("request-1", request);

        assertThatThrownBy(() -> policy.requireNewUploadAdmission("request-1", request))
                .isInstanceOf(ResumableUploadRejectedException.class)
                .satisfies(exception -> assertThat(
                        ((ResumableUploadRejectedException) exception).retryAfterSeconds()
                ).isPositive());

        policy.requireNewUploadAdmission("request-2", request);
    }

    @Test
    void deduplicatesPageAccessWithinConfiguredWindow() {
        NasProperties properties = new NasProperties();
        properties.getFileRequest().setAccessLogDedupSeconds(60);
        FileRequestPublicAccessPolicy policy = new FileRequestPublicAccessPolicy(
                new ClientIpResolver(properties), properties
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");

        assertThat(policy.shouldRecordPageAccess("request-1", request)).isTrue();
        assertThat(policy.shouldRecordPageAccess("request-1", request)).isFalse();
        assertThat(policy.shouldRecordPageAccess("request-2", request)).isTrue();
    }
}
