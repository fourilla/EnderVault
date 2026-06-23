package io.github.fourilla.endervault.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class StartupConfigBootstrap {

    public static final String CONFIG_FILE_NAME = "endervault-nas.properties";
    private static final String TEMPLATE_RESOURCE = "/endervault-nas.properties.template";
    private static final String SETUP_ACCEPTED_KEY = "nas.setup.accepted";

    private StartupConfigBootstrap() {
    }

    public static Result prepareDefault(PrintStream out) {
        return prepare(Path.of(CONFIG_FILE_NAME).toAbsolutePath().normalize(), out);
    }

    public static Result prepare(Path configFile, PrintStream out) {
        try {
            if (Files.notExists(configFile)) {
                createConfigFile(configFile);
                printCreatedMessage(configFile, out);
                return Result.CREATED;
            }

            if (!setupAccepted(configFile)) {
                printNotAcceptedMessage(configFile, out);
                return Result.NOT_ACCEPTED;
            }

            return Result.READY;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to prepare EnderVault configuration: " + configFile, ex);
        }
    }

    private static void createConfigFile(Path configFile) throws IOException {
        Path parent = configFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (InputStream inputStream = StartupConfigBootstrap.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (inputStream == null) {
                throw new IOException("Missing configuration template resource: " + TEMPLATE_RESOURCE);
            }
            Files.copy(inputStream, configFile);
        }
    }

    private static boolean setupAccepted(Path configFile) throws IOException {
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(configFile)) {
            properties.load(inputStream);
        }
        return Boolean.parseBoolean(properties.getProperty(SETUP_ACCEPTED_KEY, "false").trim());
    }

    private static void printCreatedMessage(Path configFile, PrintStream out) {
        out.println();
        out.println("EnderVault created the initial configuration file:");
        out.println("  " + configFile);
        out.println();
        out.println("Review the file, update paths/secrets/passwords, set nas.setup.accepted=true, then start EnderVault again.");
        out.println();
    }

    private static void printNotAcceptedMessage(Path configFile, PrintStream out) {
        out.println();
        out.println("EnderVault configuration is not accepted yet:");
        out.println("  " + configFile);
        out.println();
        out.println("Review the file and set nas.setup.accepted=true before starting the server.");
        out.println();
    }

    public enum Result {
        READY,
        CREATED,
        NOT_ACCEPTED
    }
}
