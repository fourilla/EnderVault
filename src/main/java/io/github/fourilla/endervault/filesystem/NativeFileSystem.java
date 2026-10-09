package io.github.fourilla.endervault.filesystem;

import java.io.IOException;
import java.nio.file.Path;

/** No native-library types may cross this internal boundary. */
interface NativeFileSystem {

    boolean supportsNoReplaceMove(Path source, Path targetParent) throws IOException;

    FileSystemOperations.MoveResult moveNoReplace(Path source, Path target) throws IOException;

    String readIdentity(Path path) throws IOException;

    boolean sameFileSystem(Path source, Path targetParent) throws IOException;
}
