package io.github.fourilla.endervault.storage;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class StorageZipWriter {

    EntryWriter open(OutputStream outputStream) {
        return open(outputStream, StorageProgressListener.NOOP);
    }

    EntryWriter open(OutputStream outputStream, StorageProgressListener progressListener) {
        return new EntryWriter(outputStream, progressListener);
    }

    static final class EntryWriter implements AutoCloseable {

        private final ZipOutputStream zipOutputStream;
        private final StorageProgressListener progress;

        private EntryWriter(OutputStream outputStream, StorageProgressListener progressListener) {
            this.zipOutputStream = new ZipOutputStream(outputStream);
            this.progress = progressListener == null ? StorageProgressListener.NOOP : progressListener;
        }

        void write(Path source, String entryName) throws IOException {
            progress.checkCanceled();
            BasicFileAttributes attributes = Files.readAttributes(
                    source,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (attributes.isSymbolicLink()) {
                throw new StorageAccessException("Symbolic links cannot be added to ZIP archives.");
            }
            String normalizedEntryName = entryName.replace('\\', '/');
            if (attributes.isDirectory()) {
                if (!normalizedEntryName.endsWith("/")) {
                    normalizedEntryName += "/";
                }
                zipOutputStream.putNextEntry(new ZipEntry(normalizedEntryName));
                zipOutputStream.closeEntry();
                progress.onItemProcessed();
                try (Stream<Path> children = Files.list(source)) {
                    for (Path child : children.sorted(pathNameComparator()).collect(Collectors.toList())) {
                        write(child, normalizedEntryName + child.getFileName());
                    }
                }
                return;
            }
            if (!attributes.isRegularFile()) {
                throw new StorageAccessException("Only regular files and directories can be added to ZIP archives.");
            }

            zipOutputStream.putNextEntry(new ZipEntry(normalizedEntryName));
            Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try (InputStream inputStream = Channels.newInputStream(Files.newByteChannel(source, options))) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    progress.checkCanceled();
                    zipOutputStream.write(buffer, 0, read);
                    progress.onBytesProcessed(read);
                }
            }
            zipOutputStream.closeEntry();
            progress.onItemProcessed();
        }

        @Override
        public void close() throws IOException {
            zipOutputStream.close();
        }

        private Comparator<Path> pathNameComparator() {
            return Comparator
                    .comparing((Path path) -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .thenComparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT));
        }
    }
}
