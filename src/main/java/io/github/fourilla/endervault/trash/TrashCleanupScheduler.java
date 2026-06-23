package io.github.fourilla.endervault.trash;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TrashCleanupScheduler {

    private static final Logger logger = LoggerFactory.getLogger(TrashCleanupScheduler.class);

    private final TrashService trashService;

    public TrashCleanupScheduler(TrashService trashService) {
        this.trashService = trashService;
    }

    @Scheduled(fixedDelayString = "${nas.trash.cleanup-interval-ms:3600000}")
    public void cleanupExpiredTrashItems() {
        try {
            int deletedCount = trashService.cleanupExpired();
            if (deletedCount > 0) {
                logger.info("Cleaned up {} expired trash item(s).", deletedCount);
            }
        } catch (IOException ex) {
            logger.warn("Failed to clean up expired trash items.", ex);
        }
    }
}
