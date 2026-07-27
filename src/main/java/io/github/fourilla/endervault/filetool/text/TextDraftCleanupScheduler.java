package io.github.fourilla.endervault.filetool.text;

import io.github.fourilla.endervault.config.NasProperties;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TextDraftCleanupScheduler {

    private static final Logger logger = LoggerFactory.getLogger(TextDraftCleanupScheduler.class);

    private final TextDraftService textDraftService;
    private final NasProperties.FileTools fileTools;
    private Instant lastCleanup = Instant.now();

    public TextDraftCleanupScheduler(TextDraftService textDraftService, NasProperties nasProperties) {
        this.textDraftService = textDraftService;
        this.fileTools = nasProperties.getFileTools();
    }

    @Scheduled(fixedDelay = 60000L)
    public synchronized void cleanupExpiredDrafts() {
        Instant now = Instant.now();
        if (Duration.between(lastCleanup, now).toMillis() < cleanupIntervalMillis()) {
            return;
        }
        lastCleanup = now;
        try {
            int deleted = textDraftService.cleanupExpired();
            if (deleted > 0) {
                logger.info("Cleaned up {} expired text draft(s).", deleted);
            }
        } catch (IOException ex) {
            logger.warn("Failed to clean up expired text drafts.", ex);
        }
    }

    private long cleanupIntervalMillis() {
        return Math.max(60000L, fileTools.getTextDraftCleanupIntervalMs());
    }
}
