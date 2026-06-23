package io.github.fourilla.endervault.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class StorageZipWriter {

    EntryWriter open(OutputStream outputStream) {
        return new EntryWriter(outputStream);
    }

    static final class EntryWriter implements AutoCloseable {

        private final ZipOutputStream zipOutputStream;

        private EntryWriter(OutputStream outputStream) {
            this.zipOutputStream = new ZipOutputStream(outputStream);
        }

        void write(Path source, String entryName) throws IOException {
            if (Files.isSymbolicLink(source)) {
                return;
            }
            String normalizedEntryName = entryName.replace('\\', '/');
            if (Files.isDirectory(source)) {
                if (!normalizedEntryName.endsWith("/")) {
                    normalizedEntryName += "/";
                }
                zipOutputStream.putNextEntry(new ZipEntry(normalizedEntryName));
                zipOutputStream.closeEntry();
                try (Stream<Path> children = Files.list(source)) {
                    for (Path child : children.sorted(pathNameComparator()).collect(Collectors.toList())) {
                        write(child, normalizedEntryName + child.getFileName());
                    }
                }
                return;
            }

            zipOutputStream.putNextEntry(new ZipEntry(normalizedEntryName));
            Files.copy(source, zipOutputStream);
            zipOutputStream.closeEntry();
        }

        @Override
        public void close() throws IOException {
            zipOutputStream.close();
        }

        private Comparator<Path> pathNameComparator() {
            return Comparator
                    .comparing((Path path) -> !Files.isDirectory(path))
                    .thenComparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT));
        }
    }
}
