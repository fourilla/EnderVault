package io.github.fourilla.endervault.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupConfigBootstrapTest {

    @TempDir
    Path tempDir;

    @Test
    void createsTemplateAndStopsWhenLocalConfigIsMissing() throws Exception {
        Path configFile = tempDir.resolve("endervault-nas.properties");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        StartupConfigBootstrap.Result result = StartupConfigBootstrap.prepare(
                configFile,
                new PrintStream(output, true, StandardCharsets.UTF_8)
        );

        assertThat(result).isEqualTo(StartupConfigBootstrap.Result.CREATED);
        assertThat(configFile).exists();
        assertThat(Files.readString(configFile))
                .contains("nas.setup.accepted=false")
                .contains("nas.storage.root=/mnt/external_drive");
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("created the initial configuration file");
    }

    @Test
    void stopsWhenSetupHasNotBeenAccepted() throws Exception {
        Path configFile = tempDir.resolve("endervault-nas.properties");
        Files.writeString(configFile, "nas.setup.accepted=false\n", StandardCharsets.UTF_8);

        StartupConfigBootstrap.Result result = StartupConfigBootstrap.prepare(configFile, nullOutput());

        assertThat(result).isEqualTo(StartupConfigBootstrap.Result.NOT_ACCEPTED);
    }

    @Test
    void runsWhenSetupHasBeenAccepted() throws Exception {
        Path configFile = tempDir.resolve("endervault-nas.properties");
        Files.writeString(configFile, "nas.setup.accepted=true\n", StandardCharsets.UTF_8);

        StartupConfigBootstrap.Result result = StartupConfigBootstrap.prepare(configFile, nullOutput());

        assertThat(result).isEqualTo(StartupConfigBootstrap.Result.READY);
    }

    private PrintStream nullOutput() {
        return new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
    }
}
