package io.github.fourilla.endervault.filerequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.pending.PendingFileDecisionAction;
import io.github.fourilla.endervault.pending.PendingFileDecisionRepository;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class FileRequestUploadServiceTest {

    @TempDir
    Path root;

    private FileRequestService requestService;
    private PendingFileDecisionService pendingService;
    private FileRequestUploadService uploadService;
    private StorageService storageService;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        TemporaryArtifactRegistry artifacts = new TemporaryArtifactRegistry();
        storageService = new StorageService(properties, new FileActionRegistry(), artifacts);
        storageService.initialize();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

        FileRequestRepository requestRepository = new FileRequestRepository(objectMapper, properties);
        requestRepository.initialize();
        requestService = new FileRequestService(
                requestRepository,
                storageService,
                new PublicLinkTokenService(),
                properties
        );
        PendingFileDecisionRepository pendingRepository = new PendingFileDecisionRepository(objectMapper, properties);
        pendingRepository.initialize();
        pendingService = new PendingFileDecisionService(
                pendingRepository,
                storageService,
                artifacts,
                List.of(new FileRequestPendingDecisionObserver(requestService))
        );
        pendingService.restoreRegistrations();
        uploadService = new FileRequestUploadService(
                requestService,
                storageService,
                pendingService,
                artifacts,
                properties
        );
    }

    @Test
    void receivesAReservedUploadAndConsumesTheTicketOnce() throws Exception {
        FileRequest request = request(UploaderNamePolicy.OPTIONAL, 16, 32, 2, List.of("txt"));
        byte[] content = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FileRequestUploadService.UploadTicket ticket = uploadService.issueTicket(
                request.token(), "note.txt", content.length, "Alice"
        );

        FileRequestUploadService.UploadReceipt receipt = uploadService.receive(
                request.token(), ticket.id(), new ByteArrayInputStream(content), content.length
        );

        assertThat(receipt.pendingDecision()).isFalse();
        assertThat(receipt.uploaderName()).isEqualTo("Alice");
        assertThat(Files.readAllBytes(root.resolve("note.txt"))).isEqualTo(content);
        assertThat(requestService.require(request.id()).acceptedFiles()).isEqualTo(1);
        assertThat(uploadService.outstandingTicketCount()).isZero();
        assertThatThrownBy(() -> uploadService.receive(
                request.token(), ticket.id(), new ByteArrayInputStream(content), content.length
        )).isInstanceOf(FileRequestUploadRejectedException.class)
                .extracting(exception -> ((FileRequestUploadRejectedException) exception).status())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void keepsConflictsPendingAndReturnsQuotaWhenTheyAreDiscarded() throws Exception {
        Files.writeString(root.resolve("note.txt"), "existing");
        FileRequest request = request(UploaderNamePolicy.REQUIRED, 16, 32, 2, List.of("txt"));
        byte[] content = "pending".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FileRequestUploadService.UploadTicket ticket = uploadService.issueTicket(
                request.token(), "note.txt", content.length, "Bob"
        );

        FileRequestUploadService.UploadReceipt receipt = uploadService.receive(
                request.token(), ticket.id(), new ByteArrayInputStream(content), content.length
        );

        assertThat(receipt.pendingDecision()).isTrue();
        assertThat(requestService.require(request.id()).acceptedFiles()).isEqualTo(1);
        assertThat(pendingService.list()).singleElement().satisfies(decision -> {
            assertThat(decision.sourceReference()).isEqualTo(request.id());
            assertThat(decision.submittedBy()).isEqualTo("Bob");
        });

        pendingService.resolve(
                pendingService.list().get(0).id(),
                PendingFileDecisionAction.DISCARD,
                null,
                false
        );

        FileRequest afterDiscard = requestService.require(request.id());
        assertThat(afterDiscard.acceptedFiles()).isZero();
        assertThat(afterDiscard.acceptedBytes()).isZero();
        assertThat(Files.readString(root.resolve("note.txt"))).isEqualTo("existing");
    }

    @Test
    void inspectorRepairOfMissingPendingDataReturnsReservedQuota() throws Exception {
        Files.writeString(root.resolve("note.txt"), "existing");
        FileRequest request = request(UploaderNamePolicy.OPTIONAL, 16, 32, 2, List.of("txt"));
        byte[] content = "pending".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FileRequestUploadService.UploadTicket ticket = uploadService.issueTicket(
                request.token(), "note.txt", content.length, "Alice"
        );
        uploadService.receive(
                request.token(), ticket.id(), new ByteArrayInputStream(content), content.length
        );
        var decision = pendingService.list().get(0);
        Files.delete(storageService.resolveFileStagingFile(decision.stagingFilename()));

        pendingService.removeMissingData(decision.id());

        assertThat(pendingService.list()).isEmpty();
        FileRequest repaired = requestService.require(request.id());
        assertThat(repaired.acceptedFiles()).isZero();
        assertThat(repaired.acceptedBytes()).isZero();
    }

    @Test
    void reservesQuotaAcrossOutstandingTickets() throws Exception {
        FileRequest request = request(UploaderNamePolicy.NONE, 4, 5, 2, List.of());
        uploadService.issueTicket(request.token(), "first.bin", 3, "ignored");

        assertThatThrownBy(() -> uploadService.issueTicket(request.token(), "second.bin", 3, null))
                .isInstanceOf(FileRequestUploadRejectedException.class)
                .hasMessageContaining("quota");
        assertThat(requestService.require(request.id()).acceptedFiles()).isZero();
    }

    @Test
    void validatesUploaderNameAndExtensionBeforeIssuingTicket() throws Exception {
        FileRequest request = request(UploaderNamePolicy.REQUIRED, 16, 32, 2, List.of("pdf"));

        assertThatThrownBy(() -> uploadService.issueTicket(request.token(), "report.pdf", 4, ""))
                .isInstanceOf(FileRequestUploadRejectedException.class)
                .hasMessageContaining("Uploader name");
        assertThatThrownBy(() -> uploadService.issueTicket(request.token(), "report.exe", 4, "Alice"))
                .isInstanceOf(FileRequestUploadRejectedException.class)
                .hasMessageContaining("extension");
        assertThat(uploadService.outstandingTicketCount()).isZero();
    }

    @Test
    void acceptsConfiguredCompoundExtensions() throws Exception {
        FileRequest request = request(UploaderNamePolicy.NONE, 16, 32, 2, List.of("tar.gz"));

        FileRequestUploadService.UploadTicket ticket = uploadService.issueTicket(
                request.token(), "backup.TAR.GZ", 4, null
        );

        assertThat(ticket.filename()).isEqualTo("backup.TAR.GZ");
    }

    private FileRequest request(
            UploaderNamePolicy uploaderNamePolicy,
            long maxFileSize,
            long maxTotalSize,
            int maxFiles,
            List<String> extensions
    ) throws Exception {
        return requestService.create(
                "Upload",
                "",
                uploaderNamePolicy,
                maxFileSize,
                maxTotalSize,
                maxFiles,
                extensions,
                7,
                null
        );
    }
}
