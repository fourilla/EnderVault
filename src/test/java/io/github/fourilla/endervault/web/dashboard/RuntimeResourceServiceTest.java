package io.github.fourilla.endervault.web.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(RuntimeResourceService.usedMemory(null, 1)).isNull();
        assertThat(RuntimeResourceService.usedMemory(100L, -1)).isNull();
        assertThat(RuntimeResourceService.usedMemory(100L, 101)).isNull();
        assertThat(RuntimeResourceService.usedMemory(100L, 30)).isEqualTo(70L);
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
}
