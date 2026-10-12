package io.github.fourilla.endervault.web.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RuntimeResourceServiceTest {

    @Test
    void preservesUnavailableMeasurementsInsteadOfReportingZero() {
        assertThat(RuntimeResourceService.percent(-1)).isNull();
        assertThat(RuntimeResourceService.percent(Double.NaN)).isNull();
        assertThat(RuntimeResourceService.percent(Double.POSITIVE_INFINITY)).isNull();
        assertThat(RuntimeResourceService.percent(1.5)).isNull();
        assertThat(RuntimeResourceService.percent(0)).isZero();
        assertThat(RuntimeResourceService.percent(0.1234)).isEqualTo(12.3);
        assertThat(RuntimeResourceService.percent(1)).isEqualTo(100);
        assertThat(RuntimeResourceService.positive(-1)).isNull();
    }

    @Test
    void samplesLocallyAndSharesTheSnapshotBetweenClients() {
        RuntimeResourceService service = new RuntimeResourceService();
        var snapshot = service.snapshot();
        assertThat(snapshot.heapUsedBytes()).isNotNegative();
        assertThat(snapshot.processors()).isPositive();
        assertThat(snapshot.uptimeMs()).isNotNegative();
        assertThat(snapshot.sampledAt()).isNotNull();
        assertThat(service.snapshot()).isSameAs(snapshot);
    }

    @Test
    void linuxMemoryPairIsIncludedInTheSharedSnapshot() {
        var reads = new AtomicInteger();
        var sampler = new SystemMemorySampler(true, () -> {
            reads.incrementAndGet();
            return List.of("MemTotal: 16777216 kB", "MemAvailable: 12582912 kB", "MemFree: 128 kB");
        });
        var service = new RuntimeResourceService(sampler);

        var snapshot = service.snapshot();

        assertThat(snapshot.memoryTotalBytes()).isEqualTo(17_179_869_184L);
        assertThat(snapshot.memoryUsedBytes()).isEqualTo(4_294_967_296L);
        assertThat(service.snapshot()).isSameAs(snapshot);
        assertThat(reads).hasValue(1);
    }
}
