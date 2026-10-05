package io.github.fourilla.endervault.web.api.v1.filerequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.filerequest.FileRequest;
import io.github.fourilla.endervault.filerequest.FileRequestDependencyException;
import io.github.fourilla.endervault.filerequest.FileRequestOperationsService;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.filerequest.UploaderNamePolicy;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
import io.github.fourilla.endervault.web.api.v1.filerequest.FileRequestApiController.BulkAction;
import io.github.fourilla.endervault.web.api.v1.filerequest.FileRequestApiController.BulkResponse;
import io.github.fourilla.endervault.web.api.v1.filerequest.FileRequestApiController.BulkStatus;
import io.github.fourilla.endervault.web.filerequest.FileRequestUrlBuilder;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FileRequestBulkApiTest {
    private final FileRequestService service = mock(FileRequestService.class);
    private final FileRequestOperationsService operations = mock(FileRequestOperationsService.class);
    private final ActivityLogService activity = mock(ActivityLogService.class);
    private final FileRequestApiController controller = new FileRequestApiController(service, operations,
            mock(FileRequestUrlBuilder.class), new PublicLinkTokenService(), activity,
            mock(FileRequestAdminQueryService.class));
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @Test
    void malformedSelectionsRejectEverythingBeforeAnyMutation() {
        var invalid = Arrays.<List<String>>asList(null, List.of(), List.of(id(1), "bad"),
                List.of(id(1), "../metadata"), List.of(id(1), "merge-" + id(2)),
                List.of("1-1-1-1-1"), List.of(id(1), id(1)), List.of(id(15), id(15).toUpperCase()),
                Arrays.asList(id(1), null), IntStream.rangeClosed(1, 201).mapToObj(FileRequestBulkApiTest::id).toList());
        for (var ids : invalid) assertThat(controller.resolveSelected(ids, BulkAction.DELETE, true, request)
                .getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(service, operations, activity);
    }

    @Test
    void actionAndExplicitConfirmationAreRequired() {
        assertThat(controller.resolveSelected(List.of(id(1)), null, true, request).getStatusCode().value()).isEqualTo(400);
        for (var action : BulkAction.values()) assertThat(controller.resolveSelected(List.of(id(1)), action, false, request)
                .getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(service, operations, activity);
    }

    @Test
    void bothActionsReuseSingleOperationsAndIndividualActivityRecordsInRequestOrder() throws Exception {
        for (var action : BulkAction.values()) {
            reset(operations, activity);
            when(operations.revoke(anyString())).thenAnswer(call -> item(call.getArgument(0)));
            when(operations.delete(anyString())).thenAnswer(call -> item(call.getArgument(0)));
            var body = body(controller.resolveSelected(List.of(id(1), id(2)), action, true, request).getBody());
            assertThat(body.ok()).isTrue();
            assertThat(body.succeededCount()).isEqualTo(2);
            assertThat(body.failedCount()).isZero();
            assertThat(body.notification().type()).isEqualTo("success");
            assertThat(body.results()).extracting(result -> result.id()).containsExactly(id(1), id(2));
            assertThat(body.results()).extracting(result -> result.status()).containsOnly(BulkStatus.APPLIED);
            var order = inOrder(operations);
            if (action == BulkAction.REVOKE) {
                order.verify(operations).revoke(id(1));
                order.verify(operations).revoke(id(2));
                verify(operations, never()).delete(anyString());
            } else {
                order.verify(operations).delete(id(1));
                order.verify(operations).delete(id(2));
                verify(operations, never()).revoke(anyString());
            }
            verifyNoMoreInteractions(operations);
            verify(activity, times(2)).record(eq("FILE_REQUEST_" + action), same(request), eq("photos"), isNull(),
                    anyString(), argThat(metadata -> metadata.containsKey("requestId")
                            && metadata.containsKey("tokenFingerprint") && !metadata.containsKey("token")));
        }
    }

    @Test
    void dependencyFailuresMissingRecordsAndIoFailuresDoNotStopLaterItemsOrExposeInternals() throws Exception {
        when(operations.delete(id(1))).thenReturn(item(id(1)));
        when(operations.delete(id(2))).thenThrow(new NoSuchFileException("private registry path"));
        when(operations.delete(id(3))).thenThrow(new FileRequestDependencyException("Resolve pending files first."));
        when(operations.delete(id(4))).thenThrow(new IOException("secret metadata path"));
        when(operations.delete(id(5))).thenThrow(new IllegalStateException("secret corruption detail"));
        when(operations.delete(id(6))).thenReturn(item(id(6)));
        var body = body(controller.resolveSelected(IntStream.rangeClosed(1, 6).mapToObj(FileRequestBulkApiTest::id).toList(),
                BulkAction.DELETE, true, request).getBody());
        assertThat(body.succeededCount()).isEqualTo(2);
        assertThat(body.failedCount()).isEqualTo(4);
        assertThat(body.notification().type()).isEqualTo("warning");
        assertThat(body.results()).extracting(result -> result.status()).containsExactly(BulkStatus.APPLIED,
                BulkStatus.NOT_FOUND, BulkStatus.REJECTED, BulkStatus.FAILED, BulkStatus.FAILED, BulkStatus.APPLIED);
        assertThat(body.results().get(2).message()).isEqualTo("Resolve pending files first.");
        assertThat(body.results()).allSatisfy(result -> assertThat(result.message()).doesNotContain("secret", "private"));
        verify(operations, times(6)).delete(anyString());
        verify(operations, never()).revoke(anyString());
        verify(activity, times(2)).record(anyString(), same(request), anyString(), isNull(), anyString(), anyMap());
    }

    @Test
    void allRejectedIsStillAProcessedEnvelopeNotAFalseSuccessSummary() throws Exception {
        when(operations.delete(anyString())).thenThrow(new StorageAccessException("Revoke active requests first."));
        var response = controller.resolveSelected(List.of(id(1), id(2)), BulkAction.DELETE, true, request);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = body(response.getBody());
        assertThat(body.ok()).isTrue();
        assertThat(body.succeededCount()).isZero();
        assertThat(body.failedCount()).isEqualTo(2);
        assertThat(body.notification().type()).isEqualTo("error");
        verifyNoInteractions(activity);
    }

    @Test
    void maximumSizeAndCanonicalNormalizationRetainBoundedRequestOrder() throws Exception {
        when(operations.revoke(anyString())).thenAnswer(call -> item(call.getArgument(0)));
        var ids = IntStream.rangeClosed(1, 200).mapToObj(FileRequestBulkApiTest::id).toList();
        var body = body(controller.resolveSelected(ids, BulkAction.REVOKE, true, request).getBody());
        assertThat(body.succeededCount()).isEqualTo(200);
        assertThat(body.results()).extracting(result -> result.id()).containsExactlyElementsOf(ids);
        body = body(controller.resolveSelected(List.of("  " + id(15).toUpperCase() + "  "), BulkAction.REVOKE, true, request).getBody());
        assertThat(body.results().getFirst().id()).isEqualTo(id(15));
    }

    @Test
    void mvcBindsRepeatedIdsAsOneEncodedRequestAndReturnsJsonWithoutRedirect() throws Exception {
        when(operations.revoke(anyString())).thenAnswer(call -> item(call.getArgument(0)));
        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/v1/file-requests/selected/resolve")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("ids", id(1), id(2))
                        .param("action", "REVOKE").param("confirmed", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.succeededCount").value(2)).andExpect(jsonPath("$.failedCount").value(0))
                .andExpect(jsonPath("$.results[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.results[1].id").value(id(2))).andExpect(jsonPath("$.redirectUrl").doesNotExist());
    }

    @Test
    void mvcRejectsMissingUnknownAndBadlyTypedArguments() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/v1/file-requests/selected/resolve").param("action", "DELETE").param("confirmed", "true"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.ok").value(false));
        mvc.perform(post("/api/v1/file-requests/selected/resolve").param("ids", id(1)).param("confirmed", "true"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/file-requests/selected/resolve").param("ids", id(1)).param("action", "UNKNOWN")
                        .param("confirmed", "true")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/file-requests/selected/resolve").param("ids", id(1)).param("action", "DELETE")
                        .param("confirmed", "not-a-boolean")).andExpect(status().isBadRequest());
        verifyNoInteractions(operations, activity);
    }

    private static String id(int index) { return "00000000-0000-0000-0000-" + String.format("%012x", index); }
    private static BulkResponse body(Object body) { return (BulkResponse) body; }
    private static FileRequest item(String id) {
        return new FileRequest(id, "token_" + id, "Request", "", "photos", UploaderNamePolicy.OPTIONAL,
                1024, 4096, 3, List.of(), 0, 0, List.of(), Instant.EPOCH, null, false);
    }
}
