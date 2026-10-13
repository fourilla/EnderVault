package io.github.fourilla.endervault.web.dashboard;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class RuntimeResourceService {

    private final SystemMemorySampler memorySampler;
    private Snapshot cached;
    private long sampledAtNanos;

    public RuntimeResourceService() {
        this(new SystemMemorySampler());
    }

    RuntimeResourceService(SystemMemorySampler memorySampler) {
        this.memorySampler = memorySampler;
    }

    // Share CPU samples across concurrent dashboard clients without keeping history.
    public synchronized Snapshot snapshot() {
        long now = System.nanoTime();
        if (cached != null && now - sampledAtNanos < 1_000_000_000L) {
            return cached;
        }
        var operatingSystem = ManagementFactory.getOperatingSystemMXBean();
        var extended = operatingSystem instanceof com.sun.management.OperatingSystemMXBean bean ? bean : null;
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var memory = memorySampler.sample(extended);
        cached = new Snapshot(
                percent(extended == null ? -1 : extended.getCpuLoad()),
                percent(extended == null ? -1 : extended.getProcessCpuLoad()),
                memory.totalBytes(),
                memory.usedBytes(),
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
