package io.github.fourilla.endervault.web.api.v1.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.web.api.v1.share.ShareApiController.BulkAction;
import io.github.fourilla.endervault.web.api.v1.share.ShareApiController.BulkResponse;
import io.github.fourilla.endervault.web.api.v1.share.ShareApiController.BulkStatus;
import io.github.fourilla.endervault.web.support.ShareUrlBuilder;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ShareBulkApiTest {
    private final ShareLinkService service = mock(ShareLinkService.class);
    private final ActivityLogService activity = mock(ActivityLogService.class);
    private final PublicLinkTokenService tokenService = new PublicLinkTokenService();
    private final NasProperties properties = new NasProperties();
    private final ShareApiController controller = new ShareApiController(service, activity, tokenService,
            mock(ShareUrlBuilder.class), properties);
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @Test
    void entireSelectionIsValidatedBeforeAnyMutation() {
        var invalid = Arrays.<List<String>>asList(null, List.of(), List.of("valid", "../metadata"),
                List.of("valid", "bad/token"), List.of("valid", "space token"), List.of("valid", " valid"),
                List.of("valid", ""), List.of("valid", "valid"), Arrays.asList("valid", null),
                List.of("valid", "한글"), IntStream.range(0, 201).mapToObj(i -> "token_" + i).toList());
        for (var tokens : invalid) assertThat(controller.resolveSelected(tokens, BulkAction.DELETE, true, request)
                .getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(service, activity);
    }

    @Test
    void supportedActionAndExplicitConfirmationAreRequired() {
        assertThat(controller.resolveSelected(List.of("valid"), null, true, request).getStatusCode().value()).isEqualTo(400);
        for (var action : BulkAction.values()) assertThat(controller.resolveSelected(List.of("valid"), action, false, request)
                .getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(service, activity);
    }

    @Test
    void reusesSingleOperationsInOrderWithIndividualFingerprintActivityRecords() throws Exception {
        for (var action : BulkAction.values()) {
            reset(service, activity);
            var body = body(controller.resolveSelected(List.of("Custom_Link", "custom_link"), action, true, request).getBody());
            assertThat(body.ok()).isTrue();
            assertThat(body.succeededCount()).isEqualTo(2);
            assertThat(body.failedCount()).isZero();
            assertThat(body.notification().type()).isEqualTo("success");
            assertThat(body.results()).extracting(result -> result.id()).containsExactly("Custom_Link", "custom_link");
            assertThat(body.results()).extracting(result -> result.status()).containsOnly(BulkStatus.APPLIED);
            var order = inOrder(service);
            if (action == BulkAction.REVOKE) {
                order.verify(service).revoke("Custom_Link");
                order.verify(service).revoke("custom_link");
            } else {
                order.verify(service).delete("Custom_Link");
                order.verify(service).delete("custom_link");
            }
            verifyNoMoreInteractions(service);
            verify(activity).record(eq("SHARE_" + action), same(request), isNull(), isNull(), anyString(),
                    eq(java.util.Map.of("tokenFingerprint", tokenService.fingerprint("Custom_Link"))));
            verify(activity).record(eq("SHARE_" + action), same(request), isNull(), isNull(), anyString(),
                    eq(java.util.Map.of("tokenFingerprint", tokenService.fingerprint("custom_link"))));
        }
    }

    @Test
    void individualFailuresDoNotPreventLaterItemsOrLeakInternalDetails() throws Exception {
        doThrow(new NoSuchFileException("private registry path")).when(service).delete("missing");
        doThrow(new StorageAccessException("private token")).when(service).delete("rejected");
        doThrow(new IOException("secret token")).when(service).delete("io_failure");
        doThrow(new IllegalStateException("secret corruption")).when(service).delete("runtime_failure");
        var body = body(controller.resolveSelected(List.of("first", "missing", "rejected", "io_failure", "runtime_failure", "last"),
                BulkAction.DELETE, true, request).getBody());
        assertThat(body.succeededCount()).isEqualTo(2);
        assertThat(body.failedCount()).isEqualTo(4);
        assertThat(body.notification().type()).isEqualTo("warning");
        assertThat(body.results()).extracting(result -> result.status()).containsExactly(BulkStatus.APPLIED,
                BulkStatus.NOT_FOUND, BulkStatus.REJECTED, BulkStatus.FAILED, BulkStatus.FAILED, BulkStatus.APPLIED);
        assertThat(body.results()).allSatisfy(result -> assertThat(result.message()).doesNotContain("secret", "private"));
        verify(service, times(6)).delete(anyString());
        verify(service, never()).revoke(anyString());
        verify(activity, times(2)).record(anyString(), same(request), isNull(), isNull(), anyString(), anyMap());
    }

    @Test
    void allFailedStillReturnsProcessedEnvelopeWithAnErrorSummary() throws Exception {
        doThrow(new IOException("secret")).when(service).revoke(anyString());
        var response = controller.resolveSelected(List.of("one", "two"), BulkAction.REVOKE, true, request);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = body(response.getBody());
        assertThat(body.ok()).isTrue();
        assertThat(body.succeededCount()).isZero();
        assertThat(body.failedCount()).isEqualTo(2);
        assertThat(body.notification().type()).isEqualTo("error");
        verifyNoInteractions(activity);
    }

    @Test
    void acceptsMaximumSizeAndExistingTokensWithoutApplyingCurrentCreationPolicy() {
        properties.getShare().setCustomTokenEnabled(false);
        properties.getShare().setCustomTokenMinLength(100);
        properties.getShare().setCustomTokenMaxLength(100);
        var tokens = IntStream.range(0, 200).mapToObj(i -> "token_" + i).toList();
        var body = body(controller.resolveSelected(tokens, BulkAction.DELETE, true, request).getBody());
        assertThat(body.succeededCount()).isEqualTo(200);
        assertThat(body.results()).extracting(result -> result.id()).containsExactlyElementsOf(tokens);
        body = body(controller.resolveSelected(List.of("x", "a".repeat(300)), BulkAction.REVOKE, true, request).getBody());
        assertThat(body.succeededCount()).isEqualTo(2);
    }

    @Test
    void mvcBindsRepeatedTokensInOneEncodedRequestWithoutRedirect() throws Exception {
        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/v1/shares/selected/resolve")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("tokens", "one", "two")
                        .param("action", "DELETE").param("confirmed", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.succeededCount").value(2)).andExpect(jsonPath("$.failedCount").value(0))
                .andExpect(jsonPath("$.results[0].id").value("one"))
                .andExpect(jsonPath("$.results[1].status").value("APPLIED"))
                .andExpect(jsonPath("$.redirectUrl").doesNotExist());
    }

    @Test
    void mvcRejectsUnknownMissingAndBadlyTypedArguments() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/v1/shares/selected/resolve").param("action", "DELETE").param("confirmed", "true"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.ok").value(false));
        mvc.perform(post("/api/v1/shares/selected/resolve").param("tokens", "one").param("confirmed", "true"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/shares/selected/resolve").param("tokens", "one").param("action", "COPY")
                        .param("confirmed", "true")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/shares/selected/resolve").param("tokens", "one").param("action", "DELETE")
                        .param("confirmed", "not-a-boolean")).andExpect(status().isBadRequest());
        verifyNoInteractions(service, activity);
    }

    private static BulkResponse body(Object body) { return (BulkResponse) body; }
}
