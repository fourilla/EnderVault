package io.github.fourilla.endervault.web.api.v1.pending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.directorytransfer.DirectoryTransferQueryService;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionAction;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.pending.PendingFileDecisionService.PendingFileDecisionResult;
import io.github.fourilla.endervault.pending.PendingFileDecisionSource;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.web.api.v1.pending.PendingFileDecisionApiController.BulkStatus;
import io.github.fourilla.endervault.web.api.v1.pending.PendingFileDecisionApiController.PendingFileDecisionBulkResponse;
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

class PendingDecisionBulkApiTest {
    private final PendingFileDecisionService pending = mock(PendingFileDecisionService.class);
    private final ActivityLogService activity = mock(ActivityLogService.class);
    private final DirectoryTransferQueryService merges = mock(DirectoryTransferQueryService.class);
    private final PendingFileDecisionApiController controller = new PendingFileDecisionApiController(pending, activity, merges);
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @Test
    void invalidSelectionIsRejectedBeforeAnyMutationIncludingAValidFirstId() {
        var invalid = Arrays.<List<String>>asList(null, List.of(), List.of(id(1), ""), List.of(id(1), "bad"),
                List.of(id(1), "../staging"), List.of(id(1), "merge-" + id(2)), List.of("1-1-1-1-1"),
                List.of(id(1), id(1)), List.of(id(15), id(15).toUpperCase()), Arrays.asList(id(1), null),
                IntStream.rangeClosed(1, 201).mapToObj(PendingDecisionBulkApiTest::id).toList());
        for (var ids : invalid) {
            assertThat(controller.resolveSelected(ids, PendingFileDecisionAction.DISCARD, false, request)
                    .getStatusCode().value()).isEqualTo(400);
        }
        verifyNoInteractions(pending, activity, merges);
    }

    @Test
    void unsupportedActionsAndMissingReplaceConfirmationRejectTheEntireRequest() {
        for (var action : Arrays.asList(null, PendingFileDecisionAction.SAVE_AS, PendingFileDecisionAction.MERGE)) {
            assertThat(controller.resolveSelected(List.of(id(1)), action, true, request).getStatusCode().value()).isEqualTo(400);
        }
        assertThat(controller.resolveSelected(List.of(id(1)), PendingFileDecisionAction.REPLACE, false, request)
                .getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(pending, activity, merges);
    }

    @Test
    void everySupportedActionUsesTheSingleResolverAndActivityLogExactlyOncePerId() throws Exception {
        for (var action : List.of(PendingFileDecisionAction.KEEP_BOTH, PendingFileDecisionAction.REPLACE, PendingFileDecisionAction.DISCARD)) {
            reset(pending, activity);
            when(pending.resolve(anyString(), eq(action), isNull(), eq(action == PendingFileDecisionAction.REPLACE)))
                    .thenAnswer(call -> resolved(call.getArgument(0), action == PendingFileDecisionAction.DISCARD));
            var body = body(controller.resolveSelected(List.of(id(1), id(2)), action,
                    action == PendingFileDecisionAction.REPLACE, request).getBody());
            assertThat(body.ok()).isTrue();
            assertThat(body.succeededCount()).isEqualTo(2);
            assertThat(body.failedCount()).isZero();
            assertThat(body.notification().type()).isEqualTo("success");
            assertThat(body.results()).extracting(item -> item.status()).containsOnly(BulkStatus.RESOLVED);
            assertThat(body.results()).extracting(item -> item.removedId()).containsExactly(id(1), id(2));
            assertThat(body.results()).extracting(item -> item.committedPath())
                    .containsExactly(action == PendingFileDecisionAction.DISCARD ? null : "target/" + id(1),
                            action == PendingFileDecisionAction.DISCARD ? null : "target/" + id(2));
            var order = inOrder(pending);
            order.verify(pending).resolve(id(1), action, null, action == PendingFileDecisionAction.REPLACE);
            order.verify(pending).resolve(id(2), action, null, action == PendingFileDecisionAction.REPLACE);
            verifyNoMoreInteractions(pending);
            verify(activity, times(2)).record(eq("PENDING_FILE_DECISION_RESOLVE"), same(request), anyString(),
                    anyString(), anyString(), anyMap());
        }
        verifyNoInteractions(merges);
    }

    @Test
    void failuresDoNotRetryRollBackOrHideLaterSuccessAndUnsafeExceptionDetails() throws Exception {
        var action = PendingFileDecisionAction.DISCARD;
        when(pending.resolve(id(1), action, null, false)).thenReturn(resolved(id(1), true));
        when(pending.resolve(id(2), action, null, false)).thenThrow(new NoSuchFileException("private-path"));
        when(pending.resolve(id(3), action, null, false)).thenThrow(new StorageAccessException("Owned by another review."));
        when(pending.resolve(id(4), action, null, false)).thenThrow(new IOException("secret internal staging path"));
        when(pending.resolve(id(5), action, null, false)).thenThrow(new IllegalStateException("secret corrupt registry"));
        when(pending.resolve(id(6), action, null, false)).thenReturn(resolved(id(6), true));
        var body = body(controller.resolveSelected(IntStream.rangeClosed(1, 6).mapToObj(PendingDecisionBulkApiTest::id).toList(),
                action, false, request).getBody());
        assertThat(body.ok()).isTrue();
        assertThat(body.succeededCount()).isEqualTo(2);
        assertThat(body.failedCount()).isEqualTo(4);
        assertThat(body.notification().type()).isEqualTo("warning");
        assertThat(body.results()).extracting(item -> item.status()).containsExactly(BulkStatus.RESOLVED,
                BulkStatus.NOT_FOUND, BulkStatus.REJECTED, BulkStatus.FAILED, BulkStatus.FAILED, BulkStatus.RESOLVED);
        assertThat(body.results().get(2).message()).isEqualTo("Owned by another review.");
        assertThat(body.results()).allSatisfy(item -> assertThat(item.message()).doesNotContain("secret", "private-path"));
        assertThat(body.results().subList(1, 5)).allSatisfy(item -> {
            assertThat(item.removedId()).isNull();
            assertThat(item.committedPath()).isNull();
        });
        verify(pending, times(6)).resolve(anyString(), eq(action), isNull(), eq(false));
        verifyNoMoreInteractions(pending);
        verify(activity, times(2)).record(anyString(), same(request), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void allUnsuccessfulResultsStillReturnAProcessedEnvelopeWithAnErrorSummary() throws Exception {
        when(pending.resolve(anyString(), any(), isNull(), anyBoolean())).thenThrow(new NoSuchFileException("missing"));
        var response = controller.resolveSelected(List.of(id(1), id(2)), PendingFileDecisionAction.KEEP_BOTH, false, request);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = body(response.getBody());
        assertThat(body.ok()).isTrue();
        assertThat(body.succeededCount()).isZero();
        assertThat(body.failedCount()).isEqualTo(2);
        assertThat(body.notification().type()).isEqualTo("error");
        verifyNoInteractions(activity);
    }

    @Test
    void maximumSizeAndCanonicalCaseNormalizationPreserveRequestOrder() throws Exception {
        when(pending.resolve(anyString(), any(), isNull(), anyBoolean())).thenAnswer(call -> resolved(call.getArgument(0), true));
        var ids = IntStream.rangeClosed(1, PendingFileDecisionApiController.MAX_BULK_ITEMS)
                .mapToObj(PendingDecisionBulkApiTest::id).toList();
        var body = body(controller.resolveSelected(ids, PendingFileDecisionAction.DISCARD, false, request).getBody());
        assertThat(body.succeededCount()).isEqualTo(200);
        assertThat(body.results()).extracting(item -> item.id()).containsExactlyElementsOf(ids);
        reset(pending);
        when(pending.resolve(id(15), PendingFileDecisionAction.DISCARD, null, false)).thenReturn(resolved(id(15), true));
        body = body(controller.resolveSelected(List.of("  " + id(15).toUpperCase() + "  "),
                PendingFileDecisionAction.DISCARD, false, request).getBody());
        assertThat(body.results().getFirst().id()).isEqualTo(id(15));
        verify(pending).resolve(id(15), PendingFileDecisionAction.DISCARD, null, false);
    }

    @Test
    void mvcBindsRepeatedIdsAndReturnsStructuredResultsWithoutRedirecting() throws Exception {
        when(pending.resolve(anyString(), eq(PendingFileDecisionAction.REPLACE), isNull(), eq(true)))
                .thenAnswer(call -> resolved(call.getArgument(0), false));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/v1/pending-decisions/resolve-selected").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("ids", id(1), id(2)).param("action", "REPLACE").param("replaceConfirmed", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.succeededCount").value(2)).andExpect(jsonPath("$.failedCount").value(0))
                .andExpect(jsonPath("$.results[0].status").value("RESOLVED"))
                .andExpect(jsonPath("$.results[1].removedId").value(id(2)));
        verify(pending).resolve(id(1), PendingFileDecisionAction.REPLACE, null, true);
        verify(pending).resolve(id(2), PendingFileDecisionAction.REPLACE, null, true);
    }

    @Test
    void mvcRejectsMissingAndUnknownArgumentsWithoutResolvingAnything() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/v1/pending-decisions/resolve-selected").param("action", "DISCARD"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.ok").value(false));
        mvc.perform(post("/api/v1/pending-decisions/resolve-selected").param("ids", id(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.notification.message").exists());
        mvc.perform(post("/api/v1/pending-decisions/resolve-selected").param("ids", id(1)).param("action", "UNKNOWN"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/pending-decisions/resolve-selected").param("ids", id(1)).param("action", "DISCARD")
                        .param("replaceConfirmed", "not-a-boolean"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(pending, activity, merges);
    }

    private static String id(int index) {
        return "00000000-0000-0000-0000-" + String.format("%012x", index);
    }

    private static PendingFileDecisionBulkResponse body(Object body) {
        return (PendingFileDecisionBulkResponse) body;
    }

    private static PendingFileDecisionResult resolved(String id, boolean discard) {
        var decision = new PendingFileDecision(id, PendingFileDecisionSource.ADMIN_UPLOAD, "staging", "target", id,
                1, Instant.EPOCH, null, null, null, false);
        var file = discard ? null : new FileItem(id, "target/" + id, false, 1, "1 B", "", Instant.EPOCH,
                "text/plain", true, false, false);
        return new PendingFileDecisionResult(decision, file, discard);
    }
}
