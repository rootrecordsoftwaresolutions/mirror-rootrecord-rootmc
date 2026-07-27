package com.rootrecord.minecraft.rootmc.metrics;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;

import com.sun.management.OperatingSystemMXBean;

public final class HostMetricsSample {

    public final double cpuPct;
    public final double ramPct;
    public final double diskUsedPct;
    public final double tps;

    public HostMetricsSample(double cpuPct, double ramPct, double diskUsedPct, double tps) {
        this.cpuPct = cpuPct;
        this.ramPct = ramPct;
        this.diskUsedPct = diskUsedPct;
        this.tps = tps;
    }

    public static HostMetricsSample capture(Path diskRoot, double tps) {
        OperatingSystemMXBean os =
                (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        double cpu = os.getProcessCpuLoad();
        if (cpu < 0) {
            cpu = os.getCpuLoad();
        }
        if (cpu < 0) {
            cpu = 0;
        }
        cpu *= 100.0;

        double ramPct = 0;
        Runtime rt = Runtime.getRuntime();
        long heapMax = rt.maxMemory();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        if (heapMax > 0) {
            // JVM heap vs Xmx — aligns with host panels (Shockbyte); OS RAM includes Linux cache.
            ramPct = (heapUsed * 100.0) / (double) heapMax;
        } else {
            long totalMem = os.getTotalMemorySize();
            long freeMem = os.getFreeMemorySize();
            if (totalMem > 0) {
                ramPct = ((double) (totalMem - freeMem) / (double) totalMem) * 100.0;
            }
        }

        double diskUsedPct = 0;
        if (diskRoot != null) {
            try {
                long total = diskRoot.toFile().getTotalSpace();
                long usable = diskRoot.toFile().getUsableSpace();
                if (total > 0) {
                    diskUsedPct = ((double) (total - usable) / (double) total) * 100.0;
                }
            } catch (Exception ignored) {
                diskUsedPct = 0;
            }
        }

        return new HostMetricsSample(cpu, ramPct, diskUsedPct, Math.max(0, tps));
    }
}
