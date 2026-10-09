package io.github.fourilla.endervault.filesystem;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemPortabilityTest {

    @TempDir
    Path root;

    @Test
    void explicitlyDisabledNativeAccessKeepsTheNioPipelineUsable() throws Exception {
        runProbe("disabled", classpath(), List.of(
                "-Dendervault.filesystem.native-enabled=false",
                "-Djna.nosys=true", "-Djna.noclasspath=true"));
    }

    @Test
    void anUnavailableJnaNativeLibraryKeepsTheNioPipelineUsable() throws Exception {
        runProbe("unavailable", classpath(), List.of(
                "-Dendervault.filesystem.native-enabled=true",
                "-Djna.nosys=true", "-Djna.noclasspath=true"));
    }

    @Test
    void aMissingJnaJarKeepsTheNioPipelineUsable() throws Exception {
        List<String> entries = Arrays.asList(classpath().split(java.util.regex.Pattern.quote(File.pathSeparator)));
        List<String> withoutJna = entries.stream().filter(entry -> !jnaJar(entry)).toList();
        assertThat(withoutJna.size()).as("The isolated classpath must actually remove JNA").isLessThan(entries.size());

        runProbe("absent", String.join(File.pathSeparator, withoutJna),
                List.of("-Dendervault.filesystem.native-enabled=true"));
    }

    private void runProbe(String mode, String classpath, List<String> properties) throws Exception {
        Path directory = Files.createDirectory(root.resolve(mode));
        Path output = directory.resolve("probe.log");
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        command.addAll(properties);
        command.add("-cp");
        command.add(classpath);
        command.add(FileSystemPortabilityProbe.class.getName());
        command.add(mode);
        command.add(directory.toAbsolutePath().toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean finished;
        try {
            finished = process.waitFor(30, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        String transcript = Files.readString(output);
        assertThat(finished).as("The isolated portability probe timed out: %s", transcript).isTrue();
        assertThat(process.exitValue()).as("The isolated portability probe failed: %s", transcript).isZero();
        assertThat(transcript).contains("PORTABILITY_OK " + mode + " nativeLoaded=false");
    }

    private static String classpath() {
        return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    }

    private static boolean jnaJar(String entry) {
        String name = Path.of(entry).getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.equals("jna.jar") || (name.startsWith("jna-") && name.endsWith(".jar"));
    }
}
