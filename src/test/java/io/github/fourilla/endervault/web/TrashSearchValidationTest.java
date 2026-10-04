package io.github.fourilla.endervault.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.search.SearchQueryException;
import io.github.fourilla.endervault.trash.TrashRecord;
import io.github.fourilla.endervault.trash.TrashService;
import io.github.fourilla.endervault.web.api.v1.trash.TrashApiController;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class TrashSearchValidationTest {
    @Test
    void invalidQueryCannotTriggerListingCleanupOrActivityLogging() {
        var service = mock(TrashService.class);
        var activity = mock(ActivityLogService.class);
        var controller = new TrashApiController(service, activity);
        assertThatThrownBy(() -> controller.list("name:any || type:invalid"))
                .isInstanceOf(SearchQueryException.class);
        verifyNoInteractions(service, activity);
    }

    @Test
    void filtersAnExistingOrderedSnapshotWithoutChangingTheWholeTrashEmptyCommand() throws Exception {
        var service = mock(TrashService.class);
        var controller = new TrashApiController(service, mock(ActivityLogService.class));
        var first = record("first", "report.pdf");
        var second = record("second", "other.txt");
        var third = record("third", "older.pdf");
        when(service.list()).thenReturn(List.of(first, second, third));
        assertThat(controller.list("extension:pdf").items()).extracting(item -> item.id())
                .containsExactly("first", "third");
        when(service.empty()).thenReturn(3);
        controller.empty(new MockHttpServletRequest());
        verify(service).empty();
    }

    private TrashRecord record(String id, String name) {
        return new TrashRecord(id, "photos/" + name, "photos", name, id, false, 1, "1 B", "File",
                Instant.EPOCH, null);
    }
}
