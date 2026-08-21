package io.github.fourilla.endervault.filecommit;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

final class FileCommitFingerprints {

    private FileCommitFingerprints() {
    }

    static FileCommitFingerprint regularFile(Path path) throws IOException {
        BasicFileAttributes attributes = attributes(path);
        if (!attributes.isRegularFile() || Files.isSymbolicLink(path)) {
            throw new StorageAccessException("File commit path is not a regular file.");
        }
        return new FileCommitFingerprint(
                attributes.size(),
                attributes.lastModifiedTime().toInstant(),
                fileKey(attributes)
        );
    }

    static FileCommitFingerprint tree(Path root, StorageProgressListener progressListener) throws IOException {
        StorageProgressListener progress = progressListener == null
                ? StorageProgressListener.NOOP
                : progressListener;
        BasicFileAttributes rootAttributes = attributes(root);
        if (rootAttributes.isRegularFile()) {
            progress.checkCanceled();
            return regularFile(root);
        }
        if (!rootAttributes.isDirectory() || Files.isSymbolicLink(root)) {
            throw new StorageAccessException("Archive commit path is not a regular file or directory.");
        }

        MessageDigest digest = sha256();
        long totalSize = 0L;
        long entryCount = 0L;
        List<Path> paths;
        try (var stream = Files.walk(root)) {
            paths = stream.sorted(Comparator.comparing(path -> normalizedRelative(root, path))).toList();
        }
        for (Path path : paths) {
            progress.checkCanceled();
            BasicFileAttributes attributes = attributes(path);
            if (attributes.isSymbolicLink() || Files.isSymbolicLink(path)
                    || (!attributes.isDirectory() && !attributes.isRegularFile())) {
                throw new StorageAccessException("Archive commit tree contains an unsupported filesystem entry.");
            }
            totalSize = saturatedAdd(totalSize, attributes.isRegularFile() ? attributes.size() : 0L);
            entryCount++;
            update(digest, normalizedRelative(root, path));
            update(digest, attributes.isDirectory() ? "D" : "F");
            update(digest, Long.toString(attributes.size()));
            update(digest, Long.toString(attributes.lastModifiedTime().toMillis()));
            update(digest, Objects.toString(attributes.fileKey(), ""));
        }
        return new FileCommitFingerprint(
                totalSize,
                rootAttributes.lastModifiedTime().toInstant(),
                fileKey(rootAttributes),
                true,
                entryCount,
                HexFormat.of().formatHex(digest.digest())
        );
    }

    static boolean matchesRegularFile(FileCommitFingerprint expected, Path path) throws IOException {
        if (expected == null || expected.directory()) {
            return false;
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            return false;
        }
        FileCommitFingerprint actual = regularFile(path);
        return expected.size() == actual.size()
                && Objects.equals(expected.modifiedAt(), actual.modifiedAt())
                && compatibleFileKey(expected.fileKey(), actual.fileKey());
    }

    static boolean matchesTree(FileCommitFingerprint expected, Path path) throws IOException {
        if (expected == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        FileCommitFingerprint actual = tree(path, StorageProgressListener.NOOP);
        if (expected.directory() != actual.directory()
                || expected.size() != actual.size()
                || expected.entryCount() != actual.entryCount()
                || !Objects.equals(expected.modifiedAt(), actual.modifiedAt())
                || !compatibleFileKey(expected.fileKey(), actual.fileKey())) {
            return false;
        }
        return !expected.directory() || Objects.equals(expected.treeDigest(), actual.treeDigest());
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean compatibleFileKey(String expected, String actual) {
        return expected == null || actual == null || Objects.equals(expected, actual);
    }

    private static String fileKey(BasicFileAttributes attributes) {
        return attributes.fileKey() == null ? null : attributes.fileKey().toString();
    }

    private static String normalizedRelative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static long saturatedAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }
}
