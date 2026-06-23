package io.github.fourilla.endervault.metadata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.common.JsonRegistry;
import io.github.fourilla.endervault.config.NasProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import org.springframework.stereotype.Repository;

@Repository
public class MetadataInspectionReportStore {

    private static final TypeReference<MetadataInspectionReport> REPORT_TYPE = new TypeReference<>() {
    };

    private final JsonRegistry<MetadataInspectionReport> registry;

    public MetadataInspectionReportStore(ObjectMapper objectMapper, NasProperties nasProperties) {
        NasProperties.Storage storage = nasProperties.getStorage();
        this.registry = new JsonRegistry<>(
                objectMapper,
                storage.getRoot()
                        .toAbsolutePath()
                        .normalize()
                        .resolve(storage.getMetadataDirectory())
                        .resolve("metadata-inspection-report.json"),
                REPORT_TYPE,
                MetadataInspectionReport::empty,
                JsonRegistry.CorruptionPolicy.BACKUP_AND_RESET
        );
    }

    @PostConstruct
    public void initialize() throws IOException {
        registry.initialize();
    }

    public synchronized MetadataInspectionReport latest() throws IOException {
        return registry.read();
    }

    public synchronized void save(MetadataInspectionReport report) throws IOException {
        registry.write(report == null ? MetadataInspectionReport.empty() : report);
    }
}
