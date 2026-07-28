package io.github.fourilla.endervault.filetool.text;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record TextSourceFingerprint(
        long size,
        long modifiedMillis,
        String sha256
) {

    public static TextSourceFingerprint capture(Path file) throws IOException {
        return new TextSourceFingerprint(
                Files.size(file),
                Files.getLastModifiedTime(file).toMillis(),
                sha256(file)
        );
    }

    public boolean matches(Path file) throws IOException {
        if (Files.size(file) != size || Files.getLastModifiedTime(file).toMillis() != modifiedMillis) {
            return false;
        }
        return sha256.equals(sha256(file));
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }

        try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

}
