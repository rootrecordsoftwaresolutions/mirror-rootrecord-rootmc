package com.rootrecord.minecraft.rootmc.metrics;

import com.rootrecord.minecraft.rootmc.RootMcPlugin;
import com.rootrecord.minecraft.rootstat.mysql.ReportingStore;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public final class HostMetricsMinuteTask {

    private static final int SAMPLES_PER_MINUTE = 60;

    private final RootMcPlugin plugin;
    private final ReportingStore reportingStore;
    private final Path diskRoot;
    private final List<HostMetricsSample> bucket = new ArrayList<>(SAMPLES_PER_MINUTE);
    private BukkitTask sampleTask;
    private Instant currentMinute = Instant.now().truncatedTo(ChronoUnit.MINUTES);

    public HostMetricsMinuteTask(RootMcPlugin plugin, ReportingStore reportingStore) {
        this.plugin = plugin;
        this.reportingStore = reportingStore;
        Path root = plugin.getServer().getWorldContainer().toPath().getParent();
        this.diskRoot = root;
    }

    public void start() {
        stop();
        sampleTask =
                plugin.getServer()
                        .getScheduler()
                        .runTaskTimerAsynchronously(plugin, this::sampleSafe, 20L, 20L);
    }

    public void stop() {
        if (sampleTask != null) {
            sampleTask.cancel();
            sampleTask = null;
        }
        synchronized (bucket) {
            bucket.clear();
        }
    }

    private void sampleSafe() {
        if (!plugin.rootMcConfig().hasServerCredentials()) {
            return;
        }
        if (plugin.getServer().getOnlinePlayers().isEmpty()) {
            synchronized (bucket) {
                bucket.clear();
                currentMinute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            }
            return;
        }
        try {
            double tps = 20.0;
            double[] values = Bukkit.getTPS();
            if (values != null && values.length > 0) {
                tps = values[0];
            }
            HostMetricsSample sample = HostMetricsSample.capture(diskRoot, tps);
            List<HostMetricsSample> flush = null;
            Instant minuteToPost = null;
            synchronized (bucket) {
                Instant nowMinute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
                if (!nowMinute.equals(currentMinute) && !bucket.isEmpty()) {
                    flush = new ArrayList<>(bucket);
                    minuteToPost = currentMinute;
                    bucket.clear();
                    currentMinute = nowMinute;
                }
                bucket.add(sample);
                if (bucket.size() >= SAMPLES_PER_MINUTE) {
                    flush = new ArrayList<>(bucket);
                    minuteToPost = currentMinute;
                    bucket.clear();
                    currentMinute = nowMinute;
                }
            }
            if (flush != null && minuteToPost != null) {
                postMinute(flush, minuteToPost);
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Host metrics sample failed: " + ex.getMessage(), ex);
        }
    }

    private void postMinute(List<HostMetricsSample> samples, Instant minuteStart) {
        if (samples.isEmpty() || reportingStore == null) {
            return;
        }
        try {
            reportingStore.upsertHostMetrics(plugin.rootMcConfig().serverId(), minuteStart, samples);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Host metrics MySQL update failed: " + ex.getMessage(), ex);
        }
    }
}
