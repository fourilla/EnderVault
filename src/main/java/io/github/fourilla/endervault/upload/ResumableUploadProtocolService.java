package io.github.fourilla.endervault.upload;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.storage.StorageService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import me.desair.tus.server.TusFileUploadService;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.disk.DiskLockingService;
import org.springframework.stereotype.Service;

@Service
public class ResumableUploadProtocolService {

    public static final String ENDPOINT_PATTERN = "/api/v1/uploads/[0-9a-fA-F-]{36}";

    private final StorageService storageService;
    private final NasProperties.Upload uploadProperties;
    private TusFileUploadService tusService;
    private EnderVaultTusStorageService tusStorage;

    public ResumableUploadProtocolService(StorageService storageService, NasProperties nasProperties) {
        this.storageService = storageService;
        this.uploadProperties = nasProperties.getUpload();
    }

    @PostConstruct
    public void initialize() throws IOException {
        Path storageRoot = storageService.resumableUploadProtocolRoot();
        tusStorage = new EnderVaultTusStorageService(storageRoot);
        tusService = new TusFileUploadService()
                .withUploadUri(ENDPOINT_PATTERN)
                .withUploadStorageService(tusStorage)
                .withUploadLockingService(new DiskLockingService(storageRoot.toString()))
                .withMaxUploadSize(Long.MAX_VALUE - 1L)
                .withUploadExpirationPeriod(Duration.ofHours(
                        uploadProperties.getResumableSessionRetentionHours()
                ).toMillis())
                .disableTusExtension("creation-with-upload")
                .disableTusExtension("checksum")
                .disableTusExtension("concatenation")
                .disableTusExtension("cors");
    }

    public void process(
            HttpServletRequest request,
            HttpServletResponse response,
            String ownerKey
    ) throws IOException {
        tusService.process(request, response, ownerKey);
    }

    public UploadInfo uploadInfo(String uploadUri, String ownerKey) throws IOException, TusException {
        return tusService.getUploadInfo(uploadUri, ownerKey);
    }

    public Path claimCompletedData(String uploadUri, String ownerKey, String sessionId)
            throws IOException, TusException {
        UploadInfo uploadInfo = tusService.getUploadInfo(uploadUri, ownerKey);
        Path protocolData = tusStorage.completedDataPath(uploadInfo);
        return storageService.claimResumableUploadData(protocolData, sessionId);
    }

    public void deleteUpload(String uploadUri, String ownerKey) throws IOException, TusException {
        tusService.deleteUpload(uploadUri, ownerKey);
    }

    public boolean uploadDataExists(String uploadUri, String ownerKey) throws IOException {
        if (uploadUri == null) {
            return false;
        }
        try {
            return tusService.getUploadInfo(uploadUri, ownerKey) != null;
        } catch (TusException ex) {
            return false;
        }
    }

    public void cleanup() throws IOException {
        tusService.cleanup();
    }
}
