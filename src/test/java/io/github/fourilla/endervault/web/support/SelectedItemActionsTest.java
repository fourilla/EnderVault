package io.github.fourilla.endervault.web.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SelectedItemActionsTest {
    @Test
    void validatesWholeSelectionAndRejectsAmbiguousOrOversizedRequests() {
        String id = UUID.randomUUID().toString();
        var invalid = Arrays.<List<String>>asList(null, List.of(), List.of(id, id), List.of(id, "../other"),
                Arrays.asList(id, null), IntStream.range(0, 201).mapToObj(i -> UUID.randomUUID().toString()).toList());
        for (var ids : invalid) assertThatThrownBy(() -> SelectedItemActions.validate(ids, "DELETE", "DELETE", true,
                SelectedItemActions::canonicalUuid)).isInstanceOf(StorageAccessException.class);
        for (String action : Arrays.asList(null, "COPY", "delete")) assertThatThrownBy(() ->
                SelectedItemActions.validate(List.of(id), action, "DELETE", true, SelectedItemActions::canonicalUuid))
                .isInstanceOf(StorageAccessException.class);
        assertThatThrownBy(() -> SelectedItemActions.validate(List.of(id), "DELETE", "DELETE", false,
                SelectedItemActions::canonicalUuid)).isInstanceOf(StorageAccessException.class);
    }

    @Test
    void onlyCanonicalUuidKeysAreAcceptedAndMaximumSelectionIsImmutable() {
        assertThat(SelectedItemActions.canonicalUuid("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")).isTrue();
        assertThat(SelectedItemActions.canonicalUuid("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")).isFalse();
        assertThat(SelectedItemActions.canonicalUuid("1-1-1-1-1")).isFalse();
        var ids = IntStream.range(0, 200).mapToObj(i -> UUID.randomUUID().toString()).toList();
        var checked = SelectedItemActions.validate(ids, "DELETE", "DELETE", true, SelectedItemActions::canonicalUuid);
        assertThat(checked).containsExactlyElementsOf(ids);
        assertThatThrownBy(() -> checked.add(UUID.randomUUID().toString())).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void reportsEachFailureWithoutStoppingLaterItemsOrExposingExceptionDetails() {
        var response = SelectedItemActions.execute(List.of("first", "missing", "rejected", "io", "runtime", "last"),
                "Items", id -> {
                    switch (id) {
                        case "missing" -> throw new NoSuchFileException("private path");
                        case "rejected" -> throw new StorageAccessException("private registry");
                        case "io" -> throw new IOException("private path");
                        case "runtime" -> throw new IllegalStateException("private state");
                        default -> { return "Done"; }
                    }
                });
        assertThat(response.ok()).isTrue();
        assertThat(response.succeededCount()).isEqualTo(2);
        assertThat(response.failedCount()).isEqualTo(4);
        assertThat(response.notification().type()).isEqualTo("warning");
        assertThat(response.results()).extracting(SelectedItemActions.ItemResult::status).containsExactly(
                SelectedItemActions.Status.APPLIED, SelectedItemActions.Status.NOT_FOUND, SelectedItemActions.Status.REJECTED,
                SelectedItemActions.Status.FAILED, SelectedItemActions.Status.FAILED, SelectedItemActions.Status.APPLIED);
        assertThat(response.results()).allSatisfy(item -> assertThat(item.message()).doesNotContain("private"));
    }
}
