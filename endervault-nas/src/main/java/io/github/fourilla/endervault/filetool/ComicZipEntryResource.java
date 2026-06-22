package io.github.fourilla.endervault.filetool;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.springframework.core.io.AbstractResource;

class ComicZipEntryResource extends AbstractResource {
    private final Path cbzFile;
    private final String entryName;
    private final String filename;
    private final long contentLength;
    private final long maxBytes;

    ComicZipEntryResource(Path cbzFile, String entryName, String filename, long contentLength, long maxBytes) {
        this.cbzFile = cbzFile;
        this.entryName = entryName;
        this.filename = filename;
        this.contentLength = contentLength;
        this.maxBytes = maxBytes;
    }

    @Override
    public String getDescription() {
        return "CBZ entry " + entryName + " from " + cbzFile;
    }

    @Override
    public String getFilename() {
        return filename;
    }

    @Override
    public long contentLength() {
        return contentLength;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        ZipFile zipFile = new ZipFile(cbzFile.toFile());
        ZipEntry entry = zipFile.getEntry(entryName);
        if (entry == null) {
            zipFile.close();
            throw new StorageAccessException("Comic page is no longer available.");
        }

        InputStream inputStream = new BoundedInputStream(zipFile.getInputStream(entry), maxBytes);
        return new FilterInputStream(inputStream) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    zipFile.close();
                }
            }
        };
    }

    private static class BoundedInputStream extends FilterInputStream {
        private final long maxBytes;
        private long bytesRead;

        BoundedInputStream(InputStream inputStream, long maxBytes) {
            super(inputStream);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                countBytes(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }

            int boundedLength = boundedLength(length);
            int read = super.read(buffer, offset, boundedLength);
            if (read > 0) {
                countBytes(read);
            }
            return read;
        }

        @Override
        public long skip(long length) throws IOException {
            if (length <= 0) {
                return 0;
            }

            long skipped = super.skip(Math.min(length, remainingBytesPlusOverflowProbe()));
            if (skipped > 0) {
                countBytes(skipped);
            }
            return skipped;
        }

        private int boundedLength(int requestedLength) {
            return (int) Math.min(requestedLength, Math.min(Integer.MAX_VALUE, remainingBytesPlusOverflowProbe()));
        }

        private long remainingBytesPlusOverflowProbe() {
            long remaining = maxBytes - bytesRead;
            if (remaining >= Long.MAX_VALUE - 1L) {
                return Long.MAX_VALUE;
            }
            return Math.max(1L, remaining + 1L);
        }

        private void countBytes(long count) throws IOException {
            bytesRead += count;
            if (bytesRead > maxBytes) {
                throw new IOException("Comic page exceeded the configured size limit.");
            }
        }
    }
}
