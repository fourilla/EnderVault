package io.github.fourilla.endervault.filerequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileRequestServiceTest {

    @TempDir
    Path root;

    private FileRequestService service;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        StorageService storageService = new StorageService(
                properties,
                new FileActionRegistry(),
                new TemporaryArtifactRegistry()
        );
        storageService.initialize();
        FileRequestRepository repository = new FileRequestRepository(
                JsonMapper.builder().findAndAddModules().build(),
                properties
        );
        repository.initialize();
        service = new FileRequestService(
                repository,
                storageService,
                new PublicLinkTokenService(),
                properties
        );
    }

    @Test
    void createsPersistentRequestWithNormalizedExtensionsAndCustomToken() throws Exception {
        Files.createDirectories(root.resolve("incoming"));

        FileRequest request = service.create(
                "Project files",
                "incoming",
                UploaderNamePolicy.REQUIRED,
                1024,
                4096,
                3,
                List.of(".JPG, png", "jpg"),
                7,
                "request_token_123"
        );

        assertThat(request.token()).isEqualTo("request_token_123");
        assertThat(request.allowedExtensions()).containsExactly("jpg", "png");
        assertThat(request.destinationPath()).isEqualTo("incoming");
        assertThat(request.uploaderNamePolicy()).isEqualTo(UploaderNamePolicy.REQUIRED);
        assertThat(service.requireUsable(request.token()).id()).isEqualTo(request.id());
        assertThat(root.resolve(".endervault/file-requests.json")).exists();
    }

    @Test
    void acceptsAndNormalizesTheVaultRootAsDestination() throws Exception {
        FileRequest request = service.create(
                "Root upload",
                "/",
                UploaderNamePolicy.OPTIONAL,
                1024,
                4096,
                3,
                List.of(),
                7,
                null
        );

        assertThat(request.destinationPath()).isEmpty();
        assertThat(service.require(request.id())).isEqualTo(request);
        assertThat(service.requireUsable(request.token())).isEqualTo(request);
    }

    @Test
    void rejectsUnsafeLimitsAndNonDirectoryDestination() throws Exception {
        Files.writeString(root.resolve("file.txt"), "not a directory");

        assertThatThrownBy(() -> service.create(
                "Invalid target", "file.txt", UploaderNamePolicy.OPTIONAL,
                1024, 4096, 3, List.of(), 7, null
        )).isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("directory");

        Files.createDirectories(root.resolve("incoming"));
        assertThatThrownBy(() -> service.create(
                "Too large", "incoming", UploaderNamePolicy.OPTIONAL,
                FileRequestService.HARD_MAX_FILE_SIZE_BYTES + 1,
                FileRequestService.HARD_MAX_TOTAL_BYTES,
                3, List.of(), 7, null
        )).isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("20 GB");
    }

    @Test
    void movesAndRevokesRequestsWithTheirDestinationDirectory() throws Exception {
        Files.createDirectories(root.resolve("incoming"));
        FileRequest request = service.create(
                "Move me", "incoming", UploaderNamePolicy.NONE,
                1024, 4096, 3, List.of(), 7, null
        );

        service.moveVaultPath("incoming", "archive/incoming");
        FileRequest moved = service.list().get(0);
        assertThat(moved.destinationPath()).isEqualTo("archive/incoming");

        service.revokeVaultPath("archive");
        assertThat(service.list().get(0).enabled()).isFalse();
        assertThatThrownBy(() -> service.requireUsable(request.token()))
                .isInstanceOf(java.nio.file.NoSuchFileException.class);
    }
}
