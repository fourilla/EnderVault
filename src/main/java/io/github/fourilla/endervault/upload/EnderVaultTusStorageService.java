package io.github.fourilla.endervault.upload;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.disk.DiskStorageService;

final class EnderVaultTusStorageService extends DiskStorageService {

    private final Path storageRoot;

    EnderVaultTusStorageService(Path storageRoot) {
        super(storageRoot.toString());
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
    }

    Path completedDataPath(UploadInfo uploadInfo) {
        if (uploadInfo == null || uploadInfo.getId() == null || uploadInfo.isUploadInProgress()) {
            throw new StorageAccessException("Resumable upload is not complete.");
        }
        Path uploadRoot = storageRoot.resolve("uploads").normalize();
        Path data = uploadRoot.resolve(uploadInfo.getId().toString()).resolve("data").normalize();
        if (!data.startsWith(uploadRoot)
                || !Files.isRegularFile(data, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(data)) {
            throw new StorageAccessException("Completed resumable upload data is unavailable.");
        }
        return data;
    }
}
