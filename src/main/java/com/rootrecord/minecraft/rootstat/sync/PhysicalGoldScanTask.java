package com.rootrecord.minecraft.rootstat.sync;

import com.rootrecord.minecraft.rootstat.RootStatBridge;
import com.rootrecord.minecraft.rootstat.economy.PhysicalGoldStorageScanner;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;
import java.util.logging.Level;

/** Periodic mint-peg scan of inventory, ender chest, shop stock, and loaded world containers. */
public final class PhysicalGoldScanTask {

    private final RootStatBridge bridge;
    private final PhysicalGoldStorageScanner scanner;
    private BukkitTask repeatingTask;

    public PhysicalGoldScanTask(RootStatBridge bridge) {
        this.bridge = bridge;
        int maxChunks = Math.max(8, bridge.config().economyMaxChunksPerSync());
        int maxShops = Math.max(16, bridge.config().physicalGoldMaxShopsPerScan());
        this.scanner = new PhysicalGoldStorageScanner(maxChunks, maxShops);
    }

    public void start() {
        stop();
        if (!bridge.config().isEconomyEnabled()
                || !bridge.config().isPhysicalGoldScanEnabled()
                || !bridge.config().hasServerCredentials()) {
            bridge.getPlugin().getLogger().fine(
                    "Physical gold storage scan disabled"
                            + " (economy=" + bridge.config().isEconomyEnabled()
                            + " scan=" + bridge.config().isPhysicalGoldScanEnabled()
                            + " credentials=" + bridge.config().hasServerCredentials() + ").");
            return;
        }
        long intervalTicks = bridge.config().physicalGoldScanIntervalMinutes() * 60L * 20L;
        long delayTicks = 1200L;
        repeatingTask = bridge.getPlugin().getServer().getScheduler().runTaskTimerAsynchronously(
                bridge.getPlugin(),
                this::runScanSafe,
                delayTicks,
                intervalTicks);
        bridge.getPlugin().getLogger().info(
                "Physical gold storage scan scheduled - first run in 60s, then every "
                        + bridge.config().physicalGoldScanIntervalMinutes()
                        + " min.");
    }

    public void stop() {
        if (repeatingTask != null) {
            repeatingTask.cancel();
            repeatingTask = null;
        }
    }

    private void runScanSafe() {
        if (bridge.getPlugin().getServer().getOnlinePlayers().isEmpty()) {
            return;
        }
        try {
            runScan();
        } catch (Exception ex) {
            bridge.getPlugin().getLogger().log(Level.WARNING, "Physical gold storage scan failed: " + ex.getMessage(), ex);
        }
    }

    private void runScan() throws Exception {
        PhysicalGoldStorageScanner.ScanResult result = scanOnMainThread();
        if (bridge.reporting() == null) {
            throw new IllegalStateException("reporting MySQL is unavailable");
        }
        bridge.reporting().replacePhysicalGold(bridge.config().serverId(), result, Instant.now());
        bridge.getPlugin().getLogger().info(
                "Physical gold storage snapshot saved to MySQL - players="
                        + result.players().size()
                        + " total="
                        + String.format(java.util.Locale.US, "%.3f", result.totalG())
                        + " G shops="
                        + result.shopsScanned()
                        + " chunks="
                        + result.chunksScanned());
    }

    private PhysicalGoldStorageScanner.ScanResult scanOnMainThread() throws InterruptedException {
        if (Bukkit.isPrimaryThread()) {
            return scanner.scan();
        }
        AtomicReference<PhysicalGoldStorageScanner.ScanResult> result = new AtomicReference<>();
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Bukkit.getScheduler().runTask(bridge.getPlugin(), () -> {
            try {
                result.set(scanner.scan());
            } catch (RuntimeException ex) {
                error.set(ex);
            } finally {
                latch.countDown();
            }
        });
        latch.await();
        if (error.get() != null) {
            throw error.get();
        }
        return result.get();
    }
}
