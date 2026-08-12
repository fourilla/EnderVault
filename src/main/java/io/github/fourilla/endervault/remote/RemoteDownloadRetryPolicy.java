package io.github.fourilla.endervault.remote;

import io.github.fourilla.endervault.config.NasProperties;
import org.springframework.stereotype.Component;

@Component
public class RemoteDownloadRetryPolicy {

    private static final long INITIAL_DELAY_MS = 500L;
    private static final long MAX_DELAY_MS = 4_000L;

    private final NasProperties nasProperties;

    public RemoteDownloadRetryPolicy(NasProperties nasProperties) {
        this.nasProperties = nasProperties;
    }

    int maxRetries() {
        return nasProperties.getRemoteDownload().getMaxRetries();
    }

    boolean retryableStatus(int status) {
        return status == 408 || status == 429 || status == 500
                || status == 502 || status == 503 || status == 504;
    }

    void awaitRetry(int retryNumber, Runnable cancellationCheck) throws InterruptedException {
        long multiplier = 1L << Math.max(0, Math.min(3, retryNumber - 1));
        long remaining = Math.min(MAX_DELAY_MS, INITIAL_DELAY_MS * multiplier);
        while (remaining > 0L) {
            cancellationCheck.run();
            long sleep = Math.min(remaining, 200L);
            Thread.sleep(sleep);
            remaining -= sleep;
        }
        cancellationCheck.run();
    }
}
