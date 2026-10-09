package io.github.fourilla.endervault.filesystem;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** Runs outside the test JVM so JNA initialization cannot leak between portability cases. */
public final class FileSystemPortabilityProbe {

    private FileSystemPortabilityProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        String mode = arguments[0];
        Path root = Path.of(arguments[1]);
        boolean jnaPresent;
        try {
            Class.forName("com.sun.jna.Native", false, FileSystemPortabilityProbe.class.getClassLoader());
            jnaPresent = true;
        } catch (ClassNotFoundException ex) {
            jnaPresent = false;
        }
        require(jnaPresent != "absent".equals(mode), "JNA classpath fixture was not applied.");

        FileSystemOperations operations = FileSystemOperations.systemDefault();
        Path source = Files.createDirectory(root.resolve("source"));
        Files.createDirectory(source.resolve("nested"));
        Files.writeString(source.resolve("nested/content.txt"), "source content");
        Path parent = Files.createDirectory(root.resolve("destination"));
        Path target = parent.resolve("source");
        require(!operations.supportsNoReplaceMove(source, parent), "Native optimization must be unavailable.");
        require(operations.moveNoReplace(source, target) == FileSystemOperations.MoveResult.UNSUPPORTED,
                "An unavailable native operation must not attempt a weaker move.");
        require(Files.isDirectory(source) && !Files.exists(target, LinkOption.NOFOLLOW_LINKS),
                "Declining the optimization changed the source or target.");

        Object nioIdentity = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        require(Objects.equals(operations.readIdentity(source), nioIdentity == null ? null : nioIdentity.toString()),
                "Identity lookup must retain the available NIO identity without requiring JNA.");
        require(operations.sameFileSystem(source, parent), "Local NIO filesystem comparison failed.");

        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(DefaultFileSystemOperations.class)) {
            FileSystemOperations bean = context.getBean(FileSystemOperations.class);
            require(!bean.supportsNoReplaceMove(source, parent), "The Spring bean unexpectedly enabled native moves.");
            require(bean.moveNoReplace(source, target) == FileSystemOperations.MoveResult.UNSUPPORTED,
                    "The Spring bean must retain safe fallback when native access is unavailable.");
            require(Objects.equals(bean.readIdentity(source), nioIdentity == null ? null : nioIdentity.toString()),
                    "Spring bean identity lookup must remain available without JNA.");
            Path staged = Files.writeString(root.resolve("spring-staged.json"), "spring new value");
            Path destination = Files.writeString(root.resolve("spring-registry.json"), "spring old value");
            bean.replace(staged, destination, FileSystemOperations.Atomicity.PREFERRED);
            require(!Files.exists(staged) && Files.readString(destination).equals("spring new value"),
                    "The Spring filesystem bean did not complete intentional replacement.");
        }

        for (FileSystemOperations.Atomicity atomicity : FileSystemOperations.Atomicity.values()) {
            Path staged = Files.writeString(root.resolve("staged-" + atomicity + ".json"), "new value");
            Path destination = Files.writeString(root.resolve("registry-" + atomicity + ".json"), "old value");
            operations.syncFile(staged);
            operations.replace(staged, destination, atomicity);
            require(!Files.exists(staged) && Files.readString(destination).equals("new value"),
                    "Intentional NIO replacement did not complete: " + atomicity);
        }

        Path atomicSource = Files.writeString(root.resolve("atomic-source.txt"), "atomic content");
        Path atomicTarget = root.resolve("atomic-target.txt");
        operations.moveAtomically(atomicSource, atomicTarget);
        require(!Files.exists(atomicSource) && Files.readString(atomicTarget).equals("atomic content"),
                "Legacy NIO atomic move failed.");
        Path preferredTarget = root.resolve("preferred-target.txt");
        operations.moveWithAtomicPreference(atomicTarget, preferredTarget);
        require(!Files.exists(atomicTarget) && Files.readString(preferredTarget).equals("atomic content"),
                "Legacy NIO preferred atomic move failed.");
        FileSystemOperations.SyncResult sync = operations.syncDirectory(root);
        require(sync != null, "Directory sync must report its support without requiring JNA.");

        // The business layer, rather than the filesystem primitive, owns copy/delete fallback.
        Path copied = Files.createDirectories(target.resolve("nested")).resolve("content.txt");
        Files.copy(source.resolve("nested/content.txt"), copied, StandardCopyOption.COPY_ATTRIBUTES);
        require(Files.readString(copied).equals("source content"), "NIO copying fallback failed.");
        Files.delete(source.resolve("nested/content.txt"));
        Files.delete(source.resolve("nested"));
        Files.delete(source);
        require(!Files.exists(source) && Files.readString(copied).equals("source content"),
                "NIO copying fallback did not preserve the content.");
        require(!Boolean.parseBoolean(System.getProperty("jna.loaded")), "JNA native support unexpectedly loaded.");
        System.out.println("PORTABILITY_OK " + mode + " nativeLoaded=false");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
