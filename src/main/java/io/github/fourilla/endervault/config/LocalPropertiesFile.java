package io.github.fourilla.endervault.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class LocalPropertiesFile {

    private final Path configFile;

    public LocalPropertiesFile() {
        this(Path.of(StartupConfigBootstrap.CONFIG_FILE_NAME).toAbsolutePath().normalize());
    }

    public LocalPropertiesFile(Path configFile) {
        this.configFile = configFile;
    }

    public Path configFile() {
        return configFile;
    }

    public void update(Map<String, String> updates, String missingComment) throws IOException {
        if (Files.notExists(configFile)) {
            throw new IOException("Local configuration file was not found: " + configFile);
        }

        List<String> lines = new ArrayList<>(Files.readAllLines(configFile, StandardCharsets.UTF_8));
        Set<String> seenKeys = new LinkedHashSet<>();

        for (int i = 0; i < lines.size(); i++) {
            String key = propertyKey(lines.get(i));
            if (key != null && updates.containsKey(key)) {
                lines.set(i, key + "=" + escapePropertyValue(updates.get(key)));
                seenKeys.add(key);
            }
        }

        List<String> missingKeys = updates.keySet().stream()
                .filter(key -> !seenKeys.contains(key))
                .toList();
        if (!missingKeys.isEmpty()) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                lines.add("");
            }
            if (missingComment != null && !missingComment.isBlank()) {
                lines.add(missingComment);
            }
            for (String key : missingKeys) {
                lines.add(key + "=" + escapePropertyValue(updates.get(key)));
            }
        }

        writeAtomically(lines);
    }

    private void writeAtomically(List<String> lines) throws IOException {
        Path parent = configFile.getParent();
        Path tempFile = Files.createTempFile(parent, "endervault-nas", ".properties.tmp");
        Files.write(tempFile, lines, StandardCharsets.UTF_8);
        try {
            Files.move(tempFile, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Files.move(tempFile, configFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String propertyKey(String line) {
        String trimmed = line.trim();
        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
            return null;
        }

        int separator = line.indexOf('=');
        if (separator < 0) {
            separator = line.indexOf(':');
        }
        if (separator < 0) {
            return null;
        }
        return line.substring(0, separator).trim();
    }

    private static String escapePropertyValue(String value) {
        return value == null ? "" : value.replace("\\", "\\\\");
    }
}
