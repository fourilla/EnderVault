package io.github.fourilla.endervault.temporary;

import io.github.fourilla.endervault.config.NasProperties;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class TemporaryArtifactRetentionPolicy {

    private final NasProperties.TemporaryArtifacts properties;

    public TemporaryArtifactRetentionPolicy(NasProperties nasProperties) {
        this.properties = nasProperties.getTemporaryArtifacts();
    }

    public Duration staleAfter() {
        return Duration.ofMinutes(Math.max(1, properties.getStaleAfterMinutes()));
    }

    public boolean isStale(Instant modifiedAt, Instant now) {
        if (modifiedAt == null || now == null || modifiedAt.isAfter(now)) {
            return false;
        }
        return Duration.between(modifiedAt, now).compareTo(staleAfter()) >= 0;
    }
}
