package io.github.fourilla.endervault.web.dashboard;

import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class SystemMemorySampler {

    private final boolean linux;
    private final MemInfoReader memInfoReader;

    SystemMemorySampler() {
        this("Linux".equals(System.getProperty("os.name", "")),
                () -> Files.readAllLines(Path.of("/proc/meminfo"), StandardCharsets.US_ASCII));
    }

    SystemMemorySampler(boolean linux, MemInfoReader memInfoReader) {
        this.linux = linux;
        this.memInfoReader = memInfoReader;
    }

    Sample sample(OperatingSystemMXBean bean) {
        if (linux) {
            try {
                Sample sample = sampleLinux();
                if (sample != null) {
                    return sample;
                }
            } catch (IOException | SecurityException | NumberFormatException | ArithmeticException ex) {
                // An unavailable or invalid Linux sample falls back as a whole to the JVM values.
            }
        }
        return bean == null ? new Sample(null, null)
                : fromRemaining(bean.getTotalMemorySize(), bean.getFreeMemorySize());
    }

    private Sample sampleLinux() throws IOException {
        Long total = null;
        Long available = null;
        for (String line : memInfoReader.read()) {
            int separator = line.indexOf(':');
            if (separator < 0) {
                continue;
            }
            String key = line.substring(0, separator).trim();
            if (!key.equals("MemTotal") && !key.equals("MemAvailable")) {
                continue;
            }
            String[] value = line.substring(separator + 1).trim().split("\\s+");
            if (value.length != 2 || !value[1].equals("kB")) {
                return null;
            }
            long bytes = Math.multiplyExact(Long.parseLong(value[0]), 1024L);
            if (key.equals("MemTotal")) {
                if (total != null) {
                    return null;
                }
                total = bytes;
            } else {
                if (available != null) {
                    return null;
                }
                available = bytes;
            }
        }
        if (total == null || available == null) {
            return null;
        }
        Sample sample = fromRemaining(total, available);
        return sample.usedBytes() == null ? null : sample;
    }

    private static Sample fromRemaining(long total, long remaining) {
        return new Sample(total > 0 ? total : null,
                total > 0 && remaining >= 0 && remaining <= total ? total - remaining : null);
    }

    @FunctionalInterface
    interface MemInfoReader {
        List<String> read() throws IOException;
    }

    record Sample(Long totalBytes, Long usedBytes) {
    }
}
