package io.github.fourilla.endervault.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.config.LocalPropertiesFile;
import io.github.fourilla.endervault.config.NasProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

class FileRequestSettingsServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndAppliesFileRequestSettings() throws Exception {
        Path configFile = tempDir.resolve("endervault-nas.properties");
        Files.writeString(configFile, "nas.setup.accepted=true\n", StandardCharsets.UTF_8);
        NasProperties properties = new NasProperties();
        FileRequestSettingsService service =
                new FileRequestSettingsService(properties, new LocalPropertiesFile(configFile));

        MultiValueMap<String, String> parameters = validParameters();
        parameters.remove("enabled");
        parameters.remove("customTokenEnabled");
        service.save(service.updateFrom(parameters));

        String saved = Files.readString(configFile, StandardCharsets.UTF_8);
        assertThat(saved)
                .contains("nas.file-request.enabled=false")
                .contains("nas.file-request.default-max-file-size-bytes=2147483648")
                .contains("nas.file-request.default-max-total-bytes=8589934592")
                .contains("nas.file-request.custom-token-enabled=false")
                .contains("nas.file-request.rate-limit-max-admissions=30");
        assertThat(properties.getFileRequest().isEnabled()).isFalse();
        assertThat(properties.getFileRequest().getDefaultMaxFiles()).isEqualTo(25);
        assertThat(properties.getFileRequest().getMaxConcurrentUploadsPerRequest()).isEqualTo(1);
        assertThat(properties.getFileRequest().isCustomTokenEnabled()).isFalse();
    }

    @Test
    void rejectsDefaultsWhoseTotalQuotaIsSmallerThanOneFile() {
        FileRequestSettingsService service =
                new FileRequestSettingsService(new NasProperties(), new LocalPropertiesFile(tempDir.resolve("config")));
        MultiValueMap<String, String> parameters = validParameters();
        parameters.set("defaultMaxFileSizeGb", "10");
        parameters.set("defaultMaxTotalGb", "5");

        assertThatThrownBy(() -> service.updateFrom(parameters))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be smaller");
    }

    @Test
    void rejectsInvertedCustomTokenLengthRange() {
        FileRequestSettingsService service =
                new FileRequestSettingsService(new NasProperties(), new LocalPropertiesFile(tempDir.resolve("config")));
        MultiValueMap<String, String> parameters = validParameters();
        parameters.set("customTokenMinLength", "80");
        parameters.set("customTokenMaxLength", "40");

        assertThatThrownBy(() -> service.updateFrom(parameters))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be smaller");
    }

    private MultiValueMap<String, String> validParameters() {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("enabled", "on");
        parameters.add("defaultExpirationDays", "14");
        parameters.add("defaultMaxFileSizeGb", "2");
        parameters.add("defaultMaxTotalGb", "8");
        parameters.add("defaultMaxFiles", "25");
        parameters.add("defaultUploaderNamePolicy", "required");
        parameters.add("customTokenEnabled", "on");
        parameters.add("customTokenMinLength", "16");
        parameters.add("customTokenMaxLength", "80");
        parameters.add("randomTokenBytes", "32");
        parameters.add("maxConcurrentUploadsPerRequest", "1");
        parameters.add("rateLimitEnabled", "on");
        parameters.add("rateLimitMaxAdmissions", "30");
        parameters.add("rateLimitWindowSeconds", "120");
        parameters.add("accessLogDedupSeconds", "300");
        return parameters;
    }
}
