package io.github.fourilla.endervault.web.dashboard;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class RuntimeResourceService {

    private Snapshot cached;
    private long sampledAtNanos;

    // Share CPU samples across concurrent dashboard clients without keeping history.
    public synchronized Snapshot snapshot() {
        long now = System.nanoTime();
        if (cached != null && now - sampledAtNanos < 1_000_000_000L) {
            return cached;
        }
        var operatingSystem = ManagementFactory.getOperatingSystemMXBean();
        var extended = operatingSystem instanceof com.sun.management.OperatingSystemMXBean bean ? bean : null;
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Long totalMemory = extended == null ? null : positive(extended.getTotalMemorySize());
        Long usedMemory = extended == null ? null : usedMemory(totalMemory, extended.getFreeMemorySize());
        cached = new Snapshot(
                percent(extended == null ? -1 : extended.getCpuLoad()),
                percent(extended == null ? -1 : extended.getProcessCpuLoad()),
                totalMemory,
                usedMemory,
                heap.getUsed(),
                positive(heap.getMax()),
                operatingSystem.getAvailableProcessors(),
                ManagementFactory.getThreadMXBean().getThreadCount(),
                ManagementFactory.getRuntimeMXBean().getUptime(),
                Instant.now()
        );
        sampledAtNanos = now;
        return cached;
    }

    static Double percent(double fraction) {
        return Double.isFinite(fraction) && fraction >= 0 && fraction <= 1
                ? Math.round(fraction * 1000) / 10.0 : null;
    }

    static Long positive(long value) {
        return value > 0 ? value : null;
    }

    static Long usedMemory(Long total, long free) {
        return total != null && free >= 0 && free <= total ? total - free : null;
    }

    public record Snapshot(
            Double cpuPercent,
            Double processCpuPercent,
            Long memoryTotalBytes,
            Long memoryUsedBytes,
            long heapUsedBytes,
            Long heapMaxBytes,
            int processors,
            int threads,
            long uptimeMs,
            Instant sampledAt
    ) {
    }
}
