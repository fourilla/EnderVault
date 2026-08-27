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

class AdvancedSettingsServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void appliesRuntimeSettingsAndPersistsRestartSettingsWithoutPretendingTheyApplied() throws Exception {
        Path configFile = tempDir.resolve("endervault-nas.properties");
        Files.writeString(configFile, "nas.setup.accepted=true\n", StandardCharsets.UTF_8);
        NasProperties properties = new NasProperties();
        AdvancedSettingsService service = new AdvancedSettingsService(
                properties,
                new LocalPropertiesFile(configFile)
        );

        MultiValueMap<String, String> runtimeUpdate = currentParameters(service);
        runtimeUpdate.set("shareDefaultExpirationDays", "5");
        runtimeUpdate.set("temporaryStaleMinutes", "45");
        runtimeUpdate.set("uploadChunkSizeMib", "16");
        boolean runtimeRestartRequired = service.save(service.updateFrom(runtimeUpdate));

        assertThat(runtimeRestartRequired).isFalse();
        assertThat(properties.getShare().getDefaultExpirationDays()).isEqualTo(5);
        assertThat(properties.getTemporaryArtifacts().getStaleAfterMinutes()).isEqualTo(45);
        assertThat(properties.getUpload().getResumableChunkSizeBytes()).isEqualTo(16L * 1024L * 1024L);

        MultiValueMap<String, String> restartUpdate = currentParameters(service);
        restartUpdate.set("uploadConcurrentChunks", "6");
        boolean restartRequired = service.save(service.updateFrom(restartUpdate));

        assertThat(restartRequired).isTrue();
        assertThat(properties.getUpload().getMaxConcurrentChunks()).isEqualTo(4);
        assertThat(Files.readString(configFile, StandardCharsets.UTF_8))
                .contains("nas.upload.resumable-chunk-size-bytes=16777216")
                .contains("nas.upload.max-concurrent-chunks=6")
                .contains("nas.share.default-expiration-days=5")
                .contains("nas.temporary-artifacts.stale-after-minutes=45");
        assertThat(fieldValue(service.currentSettings(), "uploadChunkSizeMib")).isEqualTo("16");
    }

    @Test
    void rejectsInconsistentShareAndArchiveLimits() throws Exception {
        Path configFile = tempDir.resolve("endervault-nas.properties");
        Files.writeString(configFile, "nas.setup.accepted=true\n", StandardCharsets.UTF_8);
        AdvancedSettingsService service = new AdvancedSettingsService(
                new NasProperties(),
                new LocalPropertiesFile(configFile)
        );

        MultiValueMap<String, String> invalidTokens = currentParameters(service);
        invalidTokens.set("shareCustomTokenMinLength", "80");
        invalidTokens.set("shareCustomTokenMaxLength", "20");
        assertThatThrownBy(() -> service.updateFrom(invalidTokens))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum length");

        MultiValueMap<String, String> invalidArchive = currentParameters(service);
        invalidArchive.set("archiveEntryMaxGib", "50");
        invalidArchive.set("archiveTotalMaxGib", "20");
        assertThatThrownBy(() -> service.updateFrom(invalidArchive))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("total size limit");

        MultiValueMap<String, String> invalidRpId = currentParameters(service);
        invalidRpId.set("passkeyRpId", "https://vault.example.com:8443");
        assertThatThrownBy(() -> service.updateFrom(invalidRpId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("without a scheme, port, or path");
    }

    @Test
    void protectsLoginRecoveryAndCanonicalizesPasskeyOrigins() throws Exception {
        Path configFile = tempDir.resolve("endervault-nas.properties");
        Files.writeString(configFile, "nas.setup.accepted=true\n", StandardCharsets.UTF_8);
        NasProperties properties = new NasProperties();
        AdvancedSettingsService service = new AdvancedSettingsService(
                properties,
                new LocalPropertiesFile(configFile)
        );

        MultiValueMap<String, String> normalized = currentParameters(service);
        normalized.set("passkeyAllowedOrigins", "https://vault.example.com/,http://localhost:8080");
        AdvancedSettingsService.AdvancedSettingsUpdate update = service.updateFrom(normalized);
        assertThat(update.values().get("nas.passkeys.allowed-origins"))
                .isEqualTo("https://vault.example.com,http://localhost:8080");

        properties.getPasskeys().setPasswordLoginEnabled(false);
        MultiValueMap<String, String> lockout = currentParameters(service);
        lockout.remove("passkeysEnabled");
        assertThatThrownBy(() -> service.updateFrom(lockout))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Enable ID/password login");
    }

    @Test
    void exposesEveryAdvancedGroupAndDeploymentValue() {
        AdvancedSettingsService service = new AdvancedSettingsService(
                new NasProperties(),
                new LocalPropertiesFile(tempDir.resolve("missing.properties"))
        );

        AdvancedSettingsService.AdvancedSettingsSnapshot snapshot = service.currentSettings();

        assertThat(snapshot.groups())
                .extracting(AdvancedSettingsService.SettingGroup::id)
                .containsExactly("sharing", "uploads", "thumbnails", "archive", "staging", "tasks", "metadata", "activity", "access");
        assertThat(snapshot.groups().stream().mapToInt(group -> group.fields().size()).sum()).isEqualTo(47);
        assertThat(field(snapshot, "shareDefaultExpirationDays").dependencies())
                .containsExactly("shareEnabled");
        assertThat(field(snapshot, "shareCustomTokenMinLength").dependencies())
                .containsExactly("shareEnabled", "shareCustomTokenEnabled");
        assertThat(field(snapshot, "passkeyAllowedOrigins").dependencies())
                .containsExactly("passkeysEnabled");
        assertThat(snapshot.deployment())
                .extracting(AdvancedSettingsService.ReadOnlySetting::label)
                .contains("Configuration file", "Storage root", "VPN control key file", "VPN control timeout");
    }

    private MultiValueMap<String, String> currentParameters(AdvancedSettingsService service) {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        for (AdvancedSettingsService.SettingGroup group : service.currentSettings().groups()) {
            for (AdvancedSettingsService.SettingField field : group.fields()) {
                if ("boolean".equals(field.type())) {
                    if ("true".equals(field.value())) {
                        parameters.add(field.name(), "on");
                    }
                } else {
                    parameters.add(field.name(), field.value());
                }
            }
        }
        return parameters;
    }

    private String fieldValue(
            AdvancedSettingsService.AdvancedSettingsSnapshot snapshot,
            String fieldName
    ) {
        return field(snapshot, fieldName).value();
    }

    private AdvancedSettingsService.SettingField field(
            AdvancedSettingsService.AdvancedSettingsSnapshot snapshot,
            String fieldName
    ) {
        return snapshot.groups().stream()
                .flatMap(group -> group.fields().stream())
                .filter(field -> field.name().equals(fieldName))
                .findFirst()
                .orElseThrow();
    }
}
