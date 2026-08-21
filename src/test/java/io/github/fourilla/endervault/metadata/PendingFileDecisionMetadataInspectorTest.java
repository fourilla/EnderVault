package io.github.fourilla.endervault.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.pending.PendingFileDecisionSource;
import io.github.fourilla.endervault.storage.StorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PendingFileDecisionMetadataInspectorTest {

    @TempDir
    Path root;

    @Test
    void reportsMissingDataAsRepairableAndOldDataAsReviewOnly() throws Exception {
        PendingFileDecisionService pendingService = mock(PendingFileDecisionService.class);
        StorageService storageService = mock(StorageService.class);
        Path oldStaged = root.resolve("old.tmp");
        Files.writeString(oldStaged, "old");
        PendingFileDecision missing = decision("missing", "missing.tmp", Instant.now());
        PendingFileDecision old = decision("old", "old.tmp", Instant.now().minusSeconds(40L * 24 * 3600));
        when(pendingService.list()).thenReturn(List.of(missing, old));
        when(storageService.resolveFileStagingFile("missing.tmp")).thenReturn(root.resolve("missing.tmp"));
        when(storageService.resolveFileStagingFile("old.tmp")).thenReturn(oldStaged);
        when(storageService.resolveVaultDirectory("incoming")).thenReturn(root);
        NasProperties properties = new NasProperties();
        properties.getPendingFileDecisions().setWarningDays(30);
        PendingFileDecisionMetadataInspector inspector = new PendingFileDecisionMetadataInspector(
                pendingService,
                storageService,
                properties
        );

        List<MetadataIssue> issues = inspector.inspect();

        assertThat(issues).extracting(MetadataIssue::action)
                .containsExactly(
                        MetadataIssueAction.REMOVE_MISSING_PENDING_DECISION,
                        MetadataIssueAction.NONE
                );
        assertThat(issues.get(0).severity()).isEqualTo(MetadataIssueSeverity.DANGER);
        assertThat(issues.get(1).title()).contains("waiting a long time");

        inspector.repair(MetadataIssueAction.REMOVE_MISSING_PENDING_DECISION, missing.id());
        verify(pendingService).removeMissingData(missing.id());
    }

    private PendingFileDecision decision(String id, String stagingFilename, Instant createdAt) {
        return new PendingFileDecision(
                id,
                PendingFileDecisionSource.FILE_REQUEST,
                stagingFilename,
                "incoming",
                "note.txt",
                4,
                createdAt,
                null,
                "request-id",
                "Alice"
        );
    }
}
