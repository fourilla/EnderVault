package io.github.fourilla.endervault.web.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SystemMemorySamplerTest {

    @Test
    void linuxCountsAvailableMemoryInsteadOfOnlyCompletelyFreeMemory() {
        var bean = mock(OperatingSystemMXBean.class);
        var reads = new AtomicInteger();
        var sampler = new SystemMemorySampler(true, () -> {
            reads.incrementAndGet();
            return List.of(
                    "MemTotal:       16777216 kB",
                    "MemFree:             128 kB",
                    "Buffers:         1048576 kB",
                    "Cached:         11534336 kB",
                    "MemAvailable:   12582912 kB");
        });

        var sample = sampler.sample(bean);

        assertThat(sample.totalBytes()).isEqualTo(17_179_869_184L);
        assertThat(sample.usedBytes()).isEqualTo(4_294_967_296L);
        assertThat(reads).hasValue(1);
        verifyNoInteractions(bean);
    }

    @ParameterizedTest
    @CsvSource({"0, 1024000", "1000, 0"})
    void linuxAcceptsFullyUsedAndFullyAvailableMemory(long availableKilobytes, long usedBytes) {
        var sampler = linux("MemTotal: 1000 kB\nMemAvailable: " + availableKilobytes + " kB");

        var sample = sampler.sample(null);

        assertThat(sample.totalBytes()).isEqualTo(1_024_000L);
        assertThat(sample.usedBytes()).isEqualTo(usedBytes);
    }

    @Test
    void linuxIgnoresOtherFieldsAndAcceptsTheAvailableFieldBeforeTotal() {
        var sampler = linux("MemAvailable:\t700 kB  \nMemFree: 1 kB\nHugePages_Total: 0\nMemTotal: 1000 kB");

        var sample = sampler.sample(null);

        assertThat(sample.totalBytes()).isEqualTo(1_024_000L);
        assertThat(sample.usedBytes()).isEqualTo(307_200L);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "MemFree: 10 kB",
            "MemTotal: 1000 kB",
            "MemAvailable: 700 kB",
            "MemTotal: 1000 kB\nMemAvailable: 700 kB\nMemTotal: 1000 kB",
            "MemTotal: 1000 kB\nMemAvailable: 700 kB\nMemAvailable: 600 kB",
            "MemTotal: 1000 kB\nMemAvailable: 700 kB\nMemAvailable: invalid kB",
            "MemTotal: invalid kB\nMemAvailable: 700 kB",
            "MemTotal: 1000 kB\nMemAvailable: invalid kB",
            "MemTotal: -1000 kB\nMemAvailable: 700 kB",
            "MemTotal: 1000 kB\nMemAvailable: -1 kB",
            "MemTotal: 0 kB\nMemAvailable: 0 kB",
            "MemTotal: 1000 kB\nMemAvailable: 1001 kB",
            "MemTotal: 1000 MB\nMemAvailable: 700 kB",
            "MemTotal: 1000 kB\nMemAvailable: 700 KB",
            "MemTotal: 1000\nMemAvailable: 700 kB",
            "MemTotal: 1000 kB\nMemAvailable: 700",
            "MemTotal: 1.5 kB\nMemAvailable: 1 kB",
            "MemTotal: 1000 kB\nMemAvailable: 700 kB extra",
            "MemTotal: 9223372036854775807 kB\nMemAvailable: 700 kB",
            "MemTotal: 1000 kB\nMemAvailable: 9223372036854775807 kB",
            "MemTotal: 9223372036854775808 kB\nMemAvailable: 700 kB"
    })
    void invalidLinuxMeasurementsFallBackToOneUnmixedBeanPair(String meminfo) {
        var bean = memoryBean(1000, 250);

        var sample = linux(meminfo).sample(bean);

        assertThat(sample.totalBytes()).isEqualTo(1000L);
        assertThat(sample.usedBytes()).isEqualTo(750L);
        verify(bean).getTotalMemorySize();
        verify(bean).getFreeMemorySize();
        verifyNoMoreInteractions(bean);
    }

    @Test
    void linuxReadFailureFallsBackToTheExistingBeanMeasurement() {
        var bean = memoryBean(1000, 250);
        var sampler = new SystemMemorySampler(true, () -> {
            throw new IOException("meminfo unavailable");
        });

        var sample = sampler.sample(bean);

        assertThat(sample.totalBytes()).isEqualTo(1000L);
        assertThat(sample.usedBytes()).isEqualTo(750L);
        verify(bean).getTotalMemorySize();
        verify(bean).getFreeMemorySize();
        verifyNoMoreInteractions(bean);
    }

    @Test
    void deniedLinuxReadFallsBackToTheExistingBeanMeasurement() {
        var bean = memoryBean(1000, 250);
        var sampler = new SystemMemorySampler(true, () -> {
            throw new SecurityException("meminfo denied");
        });

        var sample = sampler.sample(bean);

        assertThat(sample.totalBytes()).isEqualTo(1000L);
        assertThat(sample.usedBytes()).isEqualTo(750L);
        verify(bean).getTotalMemorySize();
        verify(bean).getFreeMemorySize();
        verifyNoMoreInteractions(bean);
    }

    @Test
    void laterSamplesRecoverAfterAReadFailure() {
        var bean = memoryBean(1000, 250);
        var reads = new AtomicInteger();
        var sampler = new SystemMemorySampler(true, () -> {
            if (reads.incrementAndGet() == 1) {
                throw new IOException("temporary failure");
            }
            return List.of("MemTotal: 2000 kB", "MemAvailable: 1500 kB");
        });

        var first = sampler.sample(bean);
        var second = sampler.sample(bean);

        assertThat(first.totalBytes()).isEqualTo(1000L);
        assertThat(first.usedBytes()).isEqualTo(750L);
        assertThat(second.totalBytes()).isEqualTo(2_048_000L);
        assertThat(second.usedBytes()).isEqualTo(512_000L);
        assertThat(reads).hasValue(2);
        verify(bean).getTotalMemorySize();
        verify(bean).getFreeMemorySize();
        verifyNoMoreInteractions(bean);
    }

    @Test
    void nonLinuxUsesTheExistingBeanPairWithoutReadingMeminfo() {
        var bean = memoryBean(1000, 250);
        var reads = new AtomicInteger();
        var sampler = new SystemMemorySampler(false, () -> {
            reads.incrementAndGet();
            return List.of("MemTotal: 2000 kB", "MemAvailable: 1500 kB");
        });

        var sample = sampler.sample(bean);

        assertThat(sample.totalBytes()).isEqualTo(1000L);
        assertThat(sample.usedBytes()).isEqualTo(750L);
        assertThat(reads).hasValue(0);
        verify(bean).getTotalMemorySize();
        verify(bean).getFreeMemorySize();
        verifyNoMoreInteractions(bean);
    }

    @ParameterizedTest
    @CsvSource({
            "-1, 0, , ",
            "0, 0, , ",
            "100, -1, 100, ",
            "100, 101, 100, ",
            "100, 0, 100, 100",
            "100, 100, 100, 0"
    })
    void fallbackPreservesUnavailableAndBoundaryBeanValues(long total, long free, Long expectedTotal,
                                                           Long expectedUsed) {
        var bean = memoryBean(total, free);

        var sample = linux("MemTotal: invalid kB").sample(bean);

        assertThat(sample.totalBytes()).isEqualTo(expectedTotal);
        assertThat(sample.usedBytes()).isEqualTo(expectedUsed);
        verify(bean).getTotalMemorySize();
        verify(bean).getFreeMemorySize();
        verifyNoMoreInteractions(bean);
    }

    @Test
    void missingBeanAndInvalidLinuxDataRemainUnavailable() {
        var sample = linux("MemTotal: 1000 kB").sample(null);

        assertThat(sample.totalBytes()).isNull();
        assertThat(sample.usedBytes()).isNull();
    }

    @Test
    void missingBeanOutsideLinuxRemainsUnavailableWithoutReadingMeminfo() {
        var reads = new AtomicInteger();
        var sampler = new SystemMemorySampler(false, () -> {
            reads.incrementAndGet();
            return List.of("MemTotal: 1000 kB", "MemAvailable: 700 kB");
        });

        var sample = sampler.sample(null);

        assertThat(sample.totalBytes()).isNull();
        assertThat(sample.usedBytes()).isNull();
        assertThat(reads).hasValue(0);
    }

    private SystemMemorySampler linux(String meminfo) {
        return new SystemMemorySampler(true, () -> meminfo.lines().toList());
    }

    private OperatingSystemMXBean memoryBean(long total, long free) {
        var bean = mock(OperatingSystemMXBean.class);
        when(bean.getTotalMemorySize()).thenReturn(total);
        when(bean.getFreeMemorySize()).thenReturn(free);
        return bean;
    }
}
