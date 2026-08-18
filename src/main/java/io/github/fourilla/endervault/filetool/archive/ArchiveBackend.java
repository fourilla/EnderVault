package io.github.fourilla.endervault.filetool.archive;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

interface ArchiveBackend {

    Set<ArchiveFormat> formats();

    ArchiveManifest scan(Path archive, ArchiveFormat format, ArchiveLimits limits) throws IOException;

    void extract(
            Path archive,
            Path outputRoot,
            ArchiveFormat format,
            ArchiveLimits limits,
            ArchiveExtractionProgress progress
    ) throws IOException;
}
