package io.github.fourilla.endervault.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.fourilla.endervault.filesystem.FileSystemOperations;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalPropertiesFileTest {
    @TempDir Path root;

    @Test
    void uncertainReplacementDoesNotRetryOrLoseTheOriginalFailure() throws IOException {
        Path target = root.resolve("settings.properties");
        Files.writeString(target, "setting=old\n");
        var fileSystem = mock(FileSystemOperations.class);
        var uncertain = new IOException("Replacement outcome could not be confirmed.");
        doAnswer(call -> {
            Files.move(call.getArgument(0, Path.class), call.getArgument(1, Path.class),
                    StandardCopyOption.REPLACE_EXISTING);
            throw uncertain;
        }).when(fileSystem).replace(any(), eq(target), eq(FileSystemOperations.Atomicity.PREFERRED));
        var properties = new LocalPropertiesFile(target, fileSystem);

        assertSame(uncertain, assertThrows(IOException.class,
                () -> properties.update(Map.of("setting", "new"), null)));
        assertEquals("new", properties.readValues().get("setting"));
        verify(fileSystem, times(1)).replace(any(), eq(target), eq(FileSystemOperations.Atomicity.PREFERRED));
        try (var files = Files.list(root)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void failedReplacementPreservesConfigurationAndCleansOwnedTemporaryFile() throws IOException {
        Path target = root.resolve("settings.properties");
        String original = "# local settings\nsetting=old\n";
        Files.writeString(target, original);
        var fileSystem = mock(FileSystemOperations.class);
        var failure = new IOException("Access denied.");
        doAnswer(call -> { throw failure; }).when(fileSystem)
                .replace(any(), eq(target), eq(FileSystemOperations.Atomicity.PREFERRED));

        var properties = new LocalPropertiesFile(target, fileSystem);
        assertSame(failure, assertThrows(IOException.class,
                () -> properties.update(Map.of("setting", "new"), null)));
        assertEquals(original, Files.readString(target));
        try (var files = Files.list(root)) {
            assertEquals(1, files.count());
        }
    }
}
