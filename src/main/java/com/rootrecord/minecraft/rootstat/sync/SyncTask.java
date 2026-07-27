package com.rootrecord.minecraft.rootstat.sync;

import com.rootrecord.minecraft.common.McDayClock;
import com.rootrecord.minecraft.rootstat.RootStatBridge;
import com.rootrecord.minecraft.rootstat.economy.EconomySnapshot;
import com.rootrecord.minecraft.rootstat.economy.shop.ReflectionShopSupport;
import com.rootrecord.minecraft.rootstat.economy.shop.RootMcShopsProvider;
import com.rootrecord.minecraft.rootstat.model.LinkedPlayer;
import com.rootrecord.minecraft.rootstat.model.McMMOPlayerSnapshot;
import com.rootrecord.minecraft.rootstat.model.PlayerPlaytimeRecord;
import com.rootrecord.minecraft.rootstat.model.ServerPlayerSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class SyncTask {

    private final RootStatBridge bridge;
    private BukkitTask repeatingTask;
    private McDayRolloverTicker mcDayTicker;
    private McDayWorldSync mcDayWorldSync;
    private BukkitTask economyDebounceTask;
    private final Map<String, BukkitTask> shopListingDebounceTasks = new ConcurrentHashMap<>();
    private final Map<String, ShopListingSyncRequest> shopListingPending = new ConcurrentHashMap<>();
    private volatile boolean running;
    private boolean rolloverRunning;
    private long queuedRolloverFirst = -1L;
    private long queuedRolloverCurrent = -1L;
    private volatile long lastEconomyOnlyPushMs;

    public SyncTask(RootStatBridge bridge) {
        this.bridge = bridge;
    }

    public void start() {
        stop();
        mcDayTicker = new McDayRolloverTicker(bridge, this::runMcDayPipeline);
        mcDayTicker.start();
        if (Bukkit.getPluginManager().isPluginEnabled("Root-Times")) {
            bridge.getPlugin().getLogger().info(
                    "Root-Times present — skipping RootMC McDayWorldSync (Times owns world daylight).");
        } else {
            mcDayWorldSync = new McDayWorldSync(bridge);
            mcDayWorldSync.start();
        }
        if (bridge.config().syncOnMcDay()) {
            bridge.getPlugin().getLogger().info(
                    "MySQL reporting active; rollover cloud work is limited to operational actions.");
        } else {
            long intervalTicks = bridge.config().syncIntervalMinutes() * 60L * 20L;
            repeatingTask = bridge.getPlugin().getServer().getScheduler().runTaskTimerAsynchronously(
                    bridge.getPlugin(), (Runnable) this::runSyncSafe, 600L, intervalTicks);
        }
    }

    public void stop() {
        if (mcDayTicker != null) {
            mcDayTicker.stop();
            mcDayTicker = null;
        }
        if (mcDayWorldSync != null) {
            mcDayWorldSync.stop();
            mcDayWorldSync = null;
        }
        if (repeatingTask != null) {
            repeatingTask.cancel();
            repeatingTask = null;
        }
        if (economyDebounceTask != null) {
            economyDebounceTask.cancel();
            economyDebounceTask = null;
        }
        for (BukkitTask task : shopListingDebounceTasks.values()) {
            task.cancel();
        }
        shopListingDebounceTasks.clear();
        shopListingPending.clear();
        rolloverRunning = false;
        queuedRolloverFirst = -1L;
        queuedRolloverCurrent = -1L;
    }

    private record ShopListingSyncRequest(boolean delete, String itemKey, String previousItemKey) {}

    /** Debounced per-shop push when stock/price changes (no full economy scan). */
    public void requestShopListingSync(String shopId, boolean deleted, String itemKey, String previousItemKey) {
        if (!bridge.config().isEconomyEnabled() || !bridge.config().hasServerCredentials()) {
            return;
        }
        if (shopId == null || shopId.isBlank()) {
            return;
        }
        shopListingPending.merge(
                shopId,
                new ShopListingSyncRequest(deleted, itemKey, previousItemKey),
                (prev, next) -> new ShopListingSyncRequest(
                        next.delete(),
                        next.itemKey() != null ? next.itemKey() : prev.itemKey(),
                        coalescePreviousItemKey(prev.previousItemKey(), next.previousItemKey())));
        BukkitTask existing = shopListingDebounceTasks.remove(shopId);
        if (existing != null) {
            existing.cancel();
        }
        shopListingDebounceTasks.put(
                shopId,
                bridge.getPlugin().getServer().getScheduler().runTaskLater(
                        bridge.getPlugin(),
                        () -> flushShopListingSync(shopId),
                        20L));
    }

    private static String coalescePreviousItemKey(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    private void flushShopListingSync(String shopId) {
        shopListingDebounceTasks.remove(shopId);
        ShopListingSyncRequest req = shopListingPending.remove(shopId);
        if (req == null) {
            return;
        }
        if (req.delete()) {
            bridge.getPlugin().getServer().getScheduler().runTaskAsynchronously(bridge.getPlugin(), () -> {
                try {
                    persistShopListingMysqlDelete(shopId, req.itemKey());
                    refreshInMemoryPrice(req.itemKey());
                    refreshInMemoryPrice(req.previousItemKey());
                } catch (Exception ex) {
                    bridge.getPlugin().getLogger().log(Level.WARNING, "Shop listing MySQL delete failed: " + shopId, ex);
                }
            });
            return;
        }
        bridge.getPlugin().getServer().getScheduler().runTask(bridge.getPlugin(), () -> {
            EconomySnapshot.ShopListingRow row = collectShopListingRow(shopId);
            bridge.getPlugin().getServer().getScheduler().runTaskAsynchronously(bridge.getPlugin(), () -> {
                try {
                    if (row == null) {
                        persistShopListingMysqlDelete(shopId, req.itemKey());
                    } else {
                        persistShopListingMysqlUpsert(row);
                    }
                    refreshInMemoryPrice(row != null ? row.itemKey() : req.itemKey());
                    refreshInMemoryPrice(req.previousItemKey());
                } catch (Exception ex) {
                    bridge.getPlugin().getLogger().log(Level.WARNING, "Shop listing MySQL update failed: " + shopId, ex);
                }
            });
        });
    }

    private void persistShopListingMysqlUpsert(EconomySnapshot.ShopListingRow row) {
        if (bridge.rootShops() == null || row == null) {
            return;
        }
        try {
            bridge.rootShops().upsertListing(row);
        } catch (Exception ex) {
            bridge.getPlugin().getLogger().log(Level.WARNING, "Shop listing MySQL upsert failed: " + row.shopId(), ex);
        }
    }

    private void persistShopListingMysqlDelete(String shopId, String itemKey) {
        if (bridge.rootShops() == null) {
            return;
        }
        try {
            bridge.rootShops().deleteListing(shopId, itemKey);
        } catch (Exception ex) {
            bridge.getPlugin().getLogger().log(Level.WARNING, "Shop listing MySQL delete failed: " + shopId, ex);
        }
    }

    private EconomySnapshot.ShopListingRow collectShopListingRow(String shopId) {
        Object exporter = RootMcShopsProvider.rootShopsExporter();
        if (exporter == null) {
            return null;
        }
        Object dto = invokeCollectListing(exporter, shopId);
        if (dto == null) {
            return null;
        }
        return mapShopListingDto(dto);
    }

    private void refreshInMemoryPrice(String itemKey) {
        if (itemKey == null || itemKey.isBlank()) {
            return;
        }
        Object exporter = RootMcShopsProvider.rootShopsExporter();
        if (exporter == null) {
            return;
        }
        Object medianObj = invokeMedian(exporter, itemKey);
        double median = medianObj instanceof Number n ? n.doubleValue() : 0;
        bridge.economy().priceRegistry().updateItem(itemKey, median);
    }

    private static Object invokeCollectListing(Object exporter, String shopId) {
        try {
            return exporter.getClass().getMethod("collectListing", String.class).invoke(exporter, shopId);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private static Object invokeMedian(Object exporter, String itemKey) {
        try {
            return exporter.getClass().getMethod("medianInStockSellPrice", String.class).invoke(exporter, itemKey);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private static EconomySnapshot.ShopListingRow mapShopListingDto(Object dto) {
        if (dto == null) {
            return null;
        }
        String shopId = str(dto, "shopId");
        String itemKey = str(dto, "itemKey");
        if (shopId == null || shopId.isBlank() || itemKey == null || itemKey.isBlank()) {
            return null;
        }
        return new EconomySnapshot.ShopListingRow(
                shopId,
                str(dto, "ownerUuid"),
                str(dto, "ownerUsername"),
                str(dto, "worldName"),
                numInt(dto, "x"),
                numInt(dto, "y"),
                numInt(dto, "z"),
                itemKey,
                numDouble(dto, "price"),
                str(dto, "listingType"),
                numInt(dto, "stockQuantity"));
    }

    private static String str(Object dto, String method) {
        Object v = ReflectionShopSupport.invokeNoArg(dto, method);
        return v == null ? null : String.valueOf(v);
    }

    private static int numInt(Object dto, String method) {
        Object v = ReflectionShopSupport.invokeNoArg(dto, method);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static double numDouble(Object dto, String method) {
        Object v = ReflectionShopSupport.invokeNoArg(dto, method);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    /** Debounced economy-only push (e.g. after quit). Throttled to avoid main-thread shop scans every minute. */
    public void requestEconomySync() {
        // Economy state is persisted directly to MySQL and read through Hyperdrive.
    }

    public void runPendingPayoutsSafe() {
        if (running || !bridge.config().hasServerCredentials()) {
            return;
        }
        if (skipWhenNoPlayersOnline(false, "Pending payout check")) {
            return;
        }
        running = true;
        try {
            applyPendingGoldTransfers(false);
            applyPendingDividends(false);
        } finally {
            running = false;
        }
    }

    private void runMcDayCloudSync(Runnable onComplete) {
        if (running) {
            bridge.getPlugin().getServer().getScheduler().runTaskLater(
                    bridge.getPlugin(), () -> runMcDayCloudSync(onComplete), 20L);
            return;
        }
        bridge.getPlugin().getServer().getScheduler().runTaskAsynchronously(bridge.getPlugin(), () -> {
            runSyncSafe(true, true);
            bridge.getPlugin().getServer().getScheduler().runTask(bridge.getPlugin(), onComplete);
        });
    }

    public void runSyncSafe() {
        runSyncSafe(false, false);
    }

    private void runSyncSafe(boolean mcDayRollover) {
        runSyncSafe(mcDayRollover, false);
    }

    private void runSyncSafe(boolean mcDayRollover, boolean skipPendingPayouts) {
        if (running) {
            return;
        }
        if (skipWhenNoPlayersOnline(false, "Scheduled cloud sync")) {
            return;
        }
        running = true;
        try {
            runSync(false, mcDayRollover, skipPendingPayouts);
        } finally {
            running = false;
        }
    }

    public int runSync(boolean verbose) {
        return runSync(verbose, false, false);
    }

    private int runSync(boolean verbose, boolean mcDayRollover) {
        return runSync(verbose, mcDayRollover, false);
    }

    public int runSync(boolean verbose, boolean mcDayRollover, boolean skipPendingPayouts) {
        if (!bridge.config().hasServerCredentials()) {
            if (verbose) {
                bridge.getPlugin().getLogger().warning("Skipping sync — server credentials not configured.");
            }
            return 0;
        }

        try {
            int count = syncLinkedPlayers(verbose);
            if (!skipPendingPayouts) {
                applyPendingGoldTransfers(verbose);
                applyPendingDividends(verbose);
            }
            persistTownySnapshot(verbose);
            if (bridge.getPlugin() instanceof com.rootrecord.minecraft.rootmc.RootMcPlugin bn) {
                bn.flushIngameEventsAsync();
            }
            return count;
        } catch (Exception ex) {
            bridge.getPlugin().getLogger().log(Level.WARNING, "Cloud sync failed: " + ex.getMessage(), ex);
            return -1;
        }
    }

    private void persistTownySnapshot(boolean verbose) {
        if (bridge.reporting() == null
                || !com.rootrecord.minecraft.rootstat.towny.TownySnapshotCollector.isAvailable()) {
            return;
        }
        try {
            var snapshot = com.rootrecord.minecraft.rootstat.towny.TownySnapshotCollector.collectSnapshot(
                    bridge.getPlugin().getLogger());
            bridge.reporting().replaceTownySnapshot(bridge.config().serverId(), snapshot);
            if (verbose) {
                bridge.getPlugin().getLogger().info("Towny reporting snapshot saved to MySQL.");
            }
        } catch (Exception ex) {
            bridge.getPlugin().getLogger().log(
                    Level.WARNING, "Towny reporting MySQL update failed: " + ex.getMessage(), ex);
        }
    }

    private int syncLinkedPlayers(boolean verbose) throws Exception {
        if (skipWhenNoPlayersOnline(verbose, "Cloud link sync")) {
            return 0;
        }
        String since = null;
        if (bridge.players() != null) {
            since = bridge.players().latestUpdatedAtIso();
        }
        List<LinkedPlayer> players = bridge.cloud().sync(since);
        int count = 0;
        if (bridge.players() != null) {
            for (LinkedPlayer player : players) {
                bridge.players().upsert(player);
                count++;
            }
        }
        if (verbose) {
            bridge.getPlugin().getLogger().info("Cloud link sync: " + count + " player(s) updated.");
        }
        return count;
    }

    private boolean skipWhenNoPlayersOnline(boolean verbose, String label) {
        if (!bridge.getPlugin().getServer().getOnlinePlayers().isEmpty()) {
            return false;
        }
        if (verbose) {
            bridge.getPlugin().getLogger().info(label + " skipped — no players online.");
        }
        return true;
    }

    private void pushServerStats(boolean verbose) {
        if (skipWhenNoPlayersOnline(verbose, "Server stats sync")) {
            return;
        }
        try {
            List<ServerPlayerSnapshot> snapshots = buildServerSnapshots();
            if (snapshots.isEmpty()) {
                if (verbose) {
                    bridge.getPlugin().getLogger().info("Server stats sync: no McMMO or playtime data to push.");
                }
                return;
            }
            int pushed = bridge.cloud().syncServerStats(snapshots);
            if (verbose) {
                bridge.getPlugin().getLogger().info("Server stats sync: " + pushed + " player(s) pushed to cloud.");
            }
        } catch (Exception ex) {
            bridge.getPlugin().getLogger().log(Level.WARNING, "Server stats sync failed: " + ex.getMessage(), ex);
        }
    }

    private void applyPendingDividends(boolean verbose) {
        try {
            int applied = bridge.economy().applyPendingDividends(bridge.cloud());
            if (verbose && applied > 0) {
                bridge.getPlugin().getLogger().info("Applied " + applied + " activity dividend payout(s).");
            }
        } catch (Exception ex) {
            bridge.getPlugin().getLogger().log(Level.WARNING, "Activity dividend apply failed: " + ex.getMessage(), ex);
        }
    }

    private void applyPendingGoldTransfers(boolean verbose) {
        if (!bridge.config().isEconomyEnabled() || !bridge.config().isEconomyVaultEnabled()) {
            return;
        }
        try {
            int applied = bridge.economy().applyPendingGoldTransfers(bridge.cloud());
            if (verbose && applied > 0) {
                bridge.getPlugin().getLogger().info("Applied " + applied + " Discord gold transfer(s) via Vault.");
            }
        } catch (Exception ex) {
            bridge.getPlugin().getLogger().log(Level.WARNING, "Discord gold transfer apply failed: " + ex.getMessage(), ex);
        }
    }

    private void pushBondSnapshot(boolean verbose) {
        org.bukkit.plugin.Plugin bonds = Bukkit.getPluginManager().getPlugin("Root-Bonds");
        if (bonds == null || !bonds.isEnabled()) {
            return;
        }
        try {
            Object cloudSync = bonds.getClass().getMethod("cloudSync").invoke(bonds);
            if (cloudSync != null) {
                cloudSync.getClass().getMethod("syncSnapshot", boolean.class).invoke(cloudSync, verbose);
            }
        } catch (ReflectiveOperationException ex) {
            bridge.getPlugin().getLogger().warning("Bond cloud sync bridge failed: " + ex.getMessage());
        }
    }

    private void pushEconomyStats(boolean verbose) {
        pushEconomyStats(verbose, false);
    }

    private void pushEconomyStats(boolean verbose, boolean allowEmptyServer) {
        if (!bridge.config().isEconomyEnabled()) {
            return;
        }
        if (!allowEmptyServer && skipWhenNoPlayersOnline(verbose, "Economy sync")) {
            return;
        }
        try {
            var snapshot = bridge.economy().collectForCloudPush();
            if (snapshot.shopPrices().isEmpty()
                    && snapshot.shopListings().isEmpty()
                    && snapshot.balances().isEmpty()
                    && snapshot.playerItems().isEmpty()
                    && snapshot.serverItems().isEmpty()
                    && snapshot.treasuryLedger().isEmpty()
                    && snapshot.playtimeMonthly().isEmpty()
                    && snapshot.townTaxRates().isEmpty()
                    && snapshot.treasuryBalance() <= 0) {
                if (verbose) {
                    bridge.getPlugin().getLogger().info("Economy sync: nothing to push.");
                }
                return;
            }
            bridge.cloud().syncEconomy(snapshot);
            bridge.getPlugin().getLogger().info(
                    "Economy sync pushed — listings="
                            + snapshot.shopListings().size()
                            + " shopItems="
                            + snapshot.shopPrices().size()
                            + " balances="
                            + snapshot.balances().size()
                            + " players="
                            + snapshot.playerItems().size()
                            + (bridge.config().isEconomyLegacyBulkSync() ? "" : " (incremental)"));
        } catch (Exception ex) {
            if (isChunkAccessFailure(ex)) {
                bridge.getPlugin().getLogger().warning(
                        "Economy sync skipped world scan (stale chunk data): " + ex.getMessage());
            } else {
                bridge.getPlugin().getLogger().log(Level.WARNING, "Economy sync failed: " + ex.getMessage(), ex);
            }
        }
    }

    private void pushTownySnapshot(boolean verbose) {
        pushTownySnapshot(verbose, false);
    }

    private void pushTownySnapshot(boolean verbose, boolean allowEmptyServer) {
        if (!com.rootrecord.minecraft.rootstat.towny.TownySnapshotCollector.isAvailable()) {
            return;
        }
        if (!allowEmptyServer && skipWhenNoPlayersOnline(verbose, "Towny sync")) {
            return;
        }
        try {
            var snapshot = com.rootrecord.minecraft.rootstat.towny.TownySnapshotCollector.collectSnapshot(
                    bridge.getPlugin().getLogger());
            bridge.cloud().syncTownySnapshot(snapshot);
            if (verbose) {
                int towns = ((java.util.List<?>) snapshot.getOrDefault("towns", java.util.List.of())).size();
                int nations = ((java.util.List<?>) snapshot.getOrDefault("nations", java.util.List.of())).size();
                bridge.getPlugin().getLogger().info("Towny Discord sync pushed — towns=" + towns + " nations=" + nations);
            }
        } catch (Exception ex) {
            bridge.getPlugin().getLogger().log(Level.WARNING, "Towny sync failed: " + ex.getMessage(), ex);
        }
    }

    private static boolean isChunkAccessFailure(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof IllegalStateException && t.getMessage() != null
                    && t.getMessage().contains("Block entity is null")) {
                return true;
            }
        }
        return false;
    }

    private List<ServerPlayerSnapshot> buildServerSnapshots() throws Exception {
        Map<String, SnapshotBuilder> merged = new LinkedHashMap<>();

        if (bridge.config().isMcmmoEnabled() && bridge.mcmmo() != null) {
            for (McMMOPlayerSnapshot row : bridge.mcmmo().readAll()) {
                merged.computeIfAbsent(row.uuid(), SnapshotBuilder::new)
                        .username(row.username())
                        .powerLevel(row.powerLevel())
                        .skills(row.skills());
            }
        }

        if (bridge.playtime() != null) {
            for (PlayerPlaytimeRecord row : bridge.playtime().readAll()) {
                merged.computeIfAbsent(row.uuid(), SnapshotBuilder::new)
                        .username(row.username())
                        .playtimeSeconds(row.totalPlaytimeSeconds())
                        .firstJoinAt(row.firstJoinAt())
                        .lastLoginAt(row.lastLoginAt());
            }
        }

        List<ServerPlayerSnapshot> out = new ArrayList<>();
        for (SnapshotBuilder builder : merged.values()) {
            out.add(builder.build());
        }
        return out;
    }

    private void runMcDayPipeline(long firstCompletedDay, long currentMcDayId) {
        if (rolloverRunning) {
            if (queuedRolloverFirst < 0) {
                queuedRolloverFirst = firstCompletedDay;
            }
            queuedRolloverCurrent = Math.max(queuedRolloverCurrent, currentMcDayId);
            return;
        }
        rolloverRunning = true;
        bridge.getPlugin().getLogger().info(
                "MC day rollover " + firstCompletedDay + " -> " + currentMcDayId
                        + ": towny, bonds, town tax, inactivity tax, payouts, heartbeat, cloud sync.");
        invokeCallbackPhase("Root-Economy", "processTownyMcDay", () -> runBondsMcDayPhase(firstCompletedDay, currentMcDayId));
    }

    private void runBondsMcDayPhase(long firstCompletedDay, long currentMcDayId) {
        invokeRangePhase("Root-Economy", "processMcDayRollover", firstCompletedDay, currentMcDayId,
                () -> runTownTaxMcDayPhase(firstCompletedDay, currentMcDayId));
    }

    private void runTownTaxMcDayPhase(long firstCompletedDay, long currentMcDayId) {
        invokeRangePhase("Root-Economy", "processMcDayTownTax", firstCompletedDay, currentMcDayId,
                () -> runUpkeepMcDayPhase(firstCompletedDay, currentMcDayId));
    }

    private void runUpkeepMcDayPhase(long firstCompletedDay, long currentMcDayId) {
        invokeRangePhase("Root-Economy", "processMcDayInactivityTax", firstCompletedDay, currentMcDayId,
                () -> runPendingPayoutPhase(this::runEconomyHeartbeatMcDayPhase));
    }

    private void runEconomyHeartbeatMcDayPhase() {
        invokeCallbackPhase("Root-Economy", "processMcDayEconomyHeartbeat", () -> {
            if (bridge.config().syncOnMcDay()) {
                runMcDayCloudSync(this::finishMcDayPipeline);
            } else {
                finishMcDayPipeline();
            }
        });
    }

    private void runPendingPayoutPhase(Runnable onComplete) {
        if (!bridge.config().hasServerCredentials()) {
            onComplete.run();
            return;
        }
        if (running) {
            bridge.getPlugin().getServer().getScheduler().runTaskLater(
                    bridge.getPlugin(), () -> runPendingPayoutPhase(onComplete), 20L);
            return;
        }
        running = true;
        bridge.getPlugin().getServer().getScheduler().runTaskAsynchronously(bridge.getPlugin(), () -> {
            try {
                applyPendingGoldTransfers(false);
                applyPendingDividends(false);
            } finally {
                running = false;
            }
            bridge.getPlugin().getServer().getScheduler().runTask(bridge.getPlugin(), onComplete);
        });
    }

    private void invokeRangePhase(
            String pluginName,
            String methodName,
            long firstCompletedDay,
            long currentMcDayId,
            Runnable onComplete) {
        Plugin target = Bukkit.getPluginManager().getPlugin(pluginName);
        if (target == null || !target.isEnabled()) {
            onComplete.run();
            return;
        }
        try {
            target.getClass()
                    .getMethod(methodName, long.class, long.class, Runnable.class)
                    .invoke(target, firstCompletedDay, currentMcDayId, onComplete);
        } catch (Exception ex) {
            bridge.getPlugin().getLogger().log(
                    Level.WARNING, "MC day phase failed for " + pluginName + ": " + ex.getMessage(), ex);
            onComplete.run();
        }
    }

    private void invokeCallbackPhase(String pluginName, String methodName, Runnable onComplete) {
        Plugin target = Bukkit.getPluginManager().getPlugin(pluginName);
        if (target == null || !target.isEnabled()) {
            onComplete.run();
            return;
        }
        try {
            target.getClass().getMethod(methodName, Runnable.class).invoke(target, onComplete);
        } catch (Exception ex) {
            bridge.getPlugin().getLogger().log(
                    Level.WARNING, "MC day phase failed for " + pluginName + ": " + ex.getMessage(), ex);
            onComplete.run();
        }
    }

    private void finishMcDayPipeline() {
        refreshGovernancePowerCache();
        rolloverRunning = false;
        bridge.getPlugin().getLogger().info("Minecraft day economy pipeline complete.");
        if (queuedRolloverFirst < 0 || queuedRolloverCurrent <= queuedRolloverFirst) {
            return;
        }
        long nextFirst = queuedRolloverFirst;
        long nextCurrent = queuedRolloverCurrent;
        queuedRolloverFirst = -1L;
        queuedRolloverCurrent = -1L;
        runMcDayPipeline(nextFirst, nextCurrent);
    }

    private void refreshGovernancePowerCache() {
        var cache = bridge.governancePower();
        if (cache != null) {
            cache.refreshAllOnlineAsync(true);
        }
    }

    private static final class SnapshotBuilder {
        private final String uuid;
        private String username;
        private Integer powerLevel;
        private Map<String, Integer> skills;
        private Long playtimeSeconds;
        private String firstJoinAt;
        private String lastLoginAt;

        SnapshotBuilder(String uuid) {
            this.uuid = uuid;
        }

        SnapshotBuilder username(String value) {
            if (value != null && !value.isBlank()) {
                username = value;
            }
            return this;
        }

        SnapshotBuilder powerLevel(int value) {
            powerLevel = value;
            return this;
        }

        SnapshotBuilder skills(Map<String, Integer> value) {
            skills = value;
            return this;
        }

        SnapshotBuilder playtimeSeconds(long value) {
            playtimeSeconds = value;
            return this;
        }

        SnapshotBuilder firstJoinAt(String value) {
            if (value != null) {
                firstJoinAt = value;
            }
            return this;
        }

        SnapshotBuilder lastLoginAt(String value) {
            if (value != null) {
                lastLoginAt = value;
            }
            return this;
        }

        ServerPlayerSnapshot build() {
            return new ServerPlayerSnapshot(
                    uuid, username, powerLevel, skills, playtimeSeconds, firstJoinAt, lastLoginAt);
        }
    }
}
