package com.rootrecord.minecraft.rootstat.economy;

import com.rootrecord.minecraft.common.RootMcTreasuryResolver;
import com.rootrecord.minecraft.common.RootMcTreasuryService;
import com.rootrecord.minecraft.common.RootMcPublicReachout;
import com.rootrecord.minecraft.common.ShadedServiceBridge;
import com.rootrecord.minecraft.rootmc.discord.DiscordActivityRewardSessions;
import com.rootrecord.minecraft.rootstat.RootStatBridge;
import com.rootrecord.minecraft.rootstat.cloud.CloudApiClient;
import com.rootrecord.minecraft.rootstat.config.RootStatConfig;
import com.rootrecord.minecraft.rootstat.economy.shop.ShopListingService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class EconomyCollector {

    private static final UUID TOWNY_SERVER_UUID =
            UUID.fromString("a73f39b0-1b7c-2930-b4a3-ce101812d926");

    private final Logger logger;
    private final RootStatBridge bridge;
    private final RootStatConfig config;
    private final boolean enabled;
    private final boolean vaultEnabled;
    private final boolean scanChests;

    private final VaultBalanceReader vault;
    private final PlayerInventoryScanner inventoryScanner;
    private final ShopListingService shopListingService;
    private final ChunkChestScanner chestScanner;
    private final ShopPriceRegistry priceRegistry = new ShopPriceRegistry();
    private final TreasurySyncCollector treasurySync;
    private final GoldFoundCollector goldFoundCollector;
    private final GoldItemEventCollector goldItemEventCollector;

    public EconomyCollector(RootStatBridge bridge) {
        this.bridge = bridge;
        this.logger = bridge.getPlugin().getLogger();
        RootStatConfig cfg = bridge.config();
        this.config = cfg;
        this.enabled = cfg.isEconomyEnabled();
        this.vaultEnabled = cfg.isEconomyVaultEnabled();
        this.scanChests = cfg.isEconomyScanChests();
        this.vault = new VaultBalanceReader(logger);
        this.inventoryScanner = new PlayerInventoryScanner();
        this.shopListingService = new ShopListingService(bridge);
        this.chestScanner = new ChunkChestScanner(cfg.economyMaxChunksPerSync());
        this.treasurySync = new TreasurySyncCollector(bridge);
        this.goldFoundCollector = new GoldFoundCollector(logger);
        this.goldItemEventCollector = new GoldItemEventCollector(logger);
    }

    public GoldItemEventCollector goldItemEventCollector() {
        return goldItemEventCollector;
    }

    public TreasurySyncCollector treasurySync() {
        return treasurySync;
    }

    public ShopPriceRegistry priceRegistry() {
        return priceRegistry;
    }

    public ShopListingService shopListingService() {
        return shopListingService;
    }

    public EconomySnapshot collect() {
        if (!enabled) {
            return EconomySnapshot.empty();
        }
        if (!Bukkit.isPrimaryThread()) {
            return collectOnMainThread();
        }
        return collectInternal();
    }

    /** Cloud push: legacy full scan, or incremental (no bulk shop chest scan). */
    public EconomySnapshot collectForCloudPush() {
        if (!enabled) {
            return EconomySnapshot.empty();
        }
        if (bridge.config().isEconomyLegacyBulkSync()) {
            return collect();
        }
        if (!Bukkit.isPrimaryThread()) {
            return collectIncrementalOnMainThread();
        }
        return collectIncrementalInternal();
    }

    private EconomySnapshot collectOnMainThread() {
        AtomicReference<EconomySnapshot> result = new AtomicReference<>(EconomySnapshot.empty());
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Bukkit.getScheduler().runTask(bridge.getPlugin(), () -> {
            try {
                result.set(collectInternal());
            } catch (RuntimeException ex) {
                error.set(ex);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return EconomySnapshot.empty();
        }
        if (error.get() != null) {
            throw error.get();
        }
        return result.get();
    }

    private EconomySnapshot collectIncrementalOnMainThread() {
        AtomicReference<EconomySnapshot> result = new AtomicReference<>(EconomySnapshot.empty());
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Bukkit.getScheduler().runTask(bridge.getPlugin(), () -> {
            try {
                result.set(collectIncrementalInternal());
            } catch (RuntimeException ex) {
                error.set(ex);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return EconomySnapshot.empty();
        }
        if (error.get() != null) {
            throw error.get();
        }
        return result.get();
    }

    private EconomySnapshot collectIncrementalInternal() {
        boolean mysqlPull = bridge.config().isEconomyMysqlPullAuthoritative();

        List<EconomySnapshot.BalanceRow> balances = List.of();
        List<EconomySnapshot.SystemBalanceRow> systemBalances = List.of();
        List<EconomySnapshot.TreasuryLedgerRow> treasuryLedger = List.of();
        List<EconomySnapshot.PlaytimeMonthlyRow> playtimeMonthly = List.of();
        double treasuryBalance = 0;

        if (!mysqlPull) {
            if (vaultEnabled) {
                vault.hook();
            }
            balances = vaultEnabled && vault.isAvailable() ? vault.readAllBalances() : List.of();
            systemBalances = vaultEnabled && vault.isAvailable() ? vault.readSystemBalances() : List.of();
            treasuryLedger = treasurySync.collectLedgerRows();
            playtimeMonthly = treasurySync.collectMonthlyPlaytime();
        }
        treasuryBalance = treasurySync.treasuryBalance();

        // Registry listings (shops.yml)  -  no world chest scan; keeps rootmc.net/market in sync.
        List<EconomySnapshot.ShopListingRow> shopListings = shopListingService.collectListings(config);
        String priceSource = shopListingService.lastActiveSummary();
        List<EconomySnapshot.ShopPriceRow> shopPrices = shopListingService.aggregatePrices(shopListings, priceSource);
        if (!shopPrices.isEmpty()) {
            priceRegistry.updateFromShopPrices(shopPrices);
        }

        List<EconomySnapshot.PlayerItemsRow> playerItems = inventoryScanner.scanOnlinePlayers();
        Map<String, Integer> serverItems = scanChests ? chestScanner.scanLoadedContainers() : Map.of();
        List<EconomySnapshot.TownTaxRow> townTaxRates = treasurySync.collectTownTaxRates();

        return new EconomySnapshot(
                shopPrices,
                shopListings,
                balances,
                systemBalances,
                playerItems,
                serverItems,
                treasuryLedger,
                playtimeMonthly,
                townTaxRates,
                goldFoundCollector.collect(),
                goldItemEventCollector.collect(),
                treasuryBalance);
    }

    private EconomySnapshot collectInternal() {
        if (vaultEnabled) {
            vault.hook();
        }

        List<EconomySnapshot.ShopListingRow> shopListings = shopListingService.collectListings(config);
        String priceSource = shopListingService.lastActiveSummary();
        List<EconomySnapshot.ShopPriceRow> shopPrices = shopListingService.aggregatePrices(shopListings, priceSource);
        priceRegistry.updateFromShopPrices(shopPrices);
        List<EconomySnapshot.BalanceRow> balances = vaultEnabled && vault.isAvailable()
                ? vault.readAllBalances()
                : List.of();
        List<EconomySnapshot.SystemBalanceRow> systemBalances = vaultEnabled && vault.isAvailable()
                ? vault.readSystemBalances()
                : List.of();
        List<EconomySnapshot.PlayerItemsRow> playerItems = inventoryScanner.scanOnlinePlayers();
        Map<String, Integer> serverItems = scanChests ? chestScanner.scanLoadedContainers() : Map.of();
        List<EconomySnapshot.TreasuryLedgerRow> treasuryLedger = treasurySync.collectLedgerRows();
        List<EconomySnapshot.PlaytimeMonthlyRow> playtimeMonthly = treasurySync.collectMonthlyPlaytime();
        List<EconomySnapshot.TownTaxRow> townTaxRates = treasurySync.collectTownTaxRates();
        double treasuryBalance = treasurySync.treasuryBalance();

        logger.fine("Economy snapshot: listings=" + shopListings.size()
                + " shopItems=" + shopPrices.size()
                + " source=" + priceSource
                + " balances=" + balances.size()
                + " players=" + playerItems.size()
                + " serverItems=" + serverItems.size()
                + " treasuryLedger=" + treasuryLedger.size());

        return new EconomySnapshot(
                shopPrices,
                shopListings,
                balances,
                systemBalances,
                playerItems,
                serverItems,
                treasuryLedger,
                playtimeMonthly,
                townTaxRates,
                goldFoundCollector.collect(),
                goldItemEventCollector.collect(),
                treasuryBalance);
    }

    public int applyPendingDividends(CloudApiClient cloud) {
        RootMcTreasuryService treasury = RootMcTreasuryResolver.resolve(bridge.getPlugin());
        if (treasury == null) {
            return 0;
        }
        try {
            List<CloudApiClient.DividendPayout> pending = cloud.fetchPendingDividendPayouts();
            if (pending.isEmpty()) {
                return 0;
            }
            if (!Bukkit.isPrimaryThread()) {
                return applyDividendsOnMainThread(cloud, treasury, pending);
            }
            return applyDividendsInternal(cloud, treasury, pending);
        } catch (Exception ex) {
            logger.warning("Activity dividend apply failed: " + ex.getMessage());
            return 0;
        }
    }

    private int applyDividendsOnMainThread(
            CloudApiClient cloud,
            RootMcTreasuryService treasury,
            List<CloudApiClient.DividendPayout> pending) {
        AtomicReference<Integer> count = new AtomicReference<>(0);
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Bukkit.getScheduler().runTask(bridge.getPlugin(), () -> {
            try {
                count.set(applyDividendsInternal(cloud, treasury, pending));
            } catch (RuntimeException ex) {
                error.set(ex);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return 0;
        }
        if (error.get() != null) {
            throw error.get();
        }
        return count.get();
    }

    private int applyDividendsInternal(
            CloudApiClient cloud,
            RootMcTreasuryService treasury,
            List<CloudApiClient.DividendPayout> pending) {
        List<CloudApiClient.DividendPayoutResult> results = new ArrayList<>();
        int applied = 0;
        for (CloudApiClient.DividendPayout payout : pending) {
            UUID uuid = parseUuid(payout.minecraftUuid());
            if (uuid == null) {
                results.add(new CloudApiClient.DividendPayoutResult(payout.id(), "failed", "invalid_uuid"));
                continue;
            }
            String recipientName = resolveRecipientName(uuid, payout.minecraftUsername());
            boolean ok = treasury.payDividend(uuid, recipientName, payout.amount(), payout.monthKey());
            if (ok) {
                applied++;
                results.add(new CloudApiClient.DividendPayoutResult(payout.id(), "applied", null));
                var player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    player.sendMessage("\u00A7aTreasury payout: \u00A7f+" + formatGold(payout.amount())
                            + " G\u00A7a for \u00A7f" + payout.monthKey() + "\u00A7a playtime.");
                }
            } else {
                results.add(new CloudApiClient.DividendPayoutResult(payout.id(), "failed", "treasury_payout_failed"));
            }
        }
        try {
            cloud.completeDividendPayouts(results);
        } catch (Exception ex) {
            logger.warning("Activity dividend complete callback failed: " + ex.getMessage());
        }
        return applied;
    }

    private static String resolveRecipientName(UUID uuid, String minecraftUsername) {
        if (minecraftUsername != null && !minecraftUsername.isBlank()) {
            return minecraftUsername;
        }
        var offline = Bukkit.getOfflinePlayer(uuid);
        if (offline != null && offline.getName() != null && !offline.getName().isBlank()) {
            return offline.getName();
        }
        // MySQL balance row requires a non-null username value.
        return "player-" + uuid.toString().substring(0, 8);
    }

    private static String formatGold(double gold) {
        if (gold == Math.rint(gold)) {
            return String.valueOf((long) gold);
        }
        return String.format(java.util.Locale.US, "%.3f", gold);
    }

    public int applyPendingGoldTransfers(CloudApiClient cloud) {
        if (!vaultEnabled || !vault.isAvailable()) {
            return 0;
        }
        RootMcTreasuryService treasury = RootMcTreasuryResolver.resolve(bridge.getPlugin());
        if (treasury == null) {
            logger.warning("Discord gold transfer skipped: treasury unavailable");
            return 0;
        }
        try {
            List<CloudApiClient.GoldTransfer> pending = cloud.fetchPendingGoldTransfers();
            if (pending.isEmpty()) {
                return 0;
            }
            if (!Bukkit.isPrimaryThread()) {
                return applyTransfersOnMainThread(cloud, treasury, pending);
            }
            return applyTransfersInternal(cloud, treasury, pending);
        } catch (Exception ex) {
            logger.warning("Discord gold transfer fetch failed: " + ex.getMessage());
            return 0;
        }
    }

    private int applyTransfersOnMainThread(
            CloudApiClient cloud,
            RootMcTreasuryService treasury,
            List<CloudApiClient.GoldTransfer> pending) {
        AtomicReference<Integer> count = new AtomicReference<>(0);
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Bukkit.getScheduler().runTask(bridge.getPlugin(), () -> {
            try {
                count.set(applyTransfersInternal(cloud, treasury, pending));
            } catch (RuntimeException ex) {
                error.set(ex);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return 0;
        }
        if (error.get() != null) {
            throw error.get();
        }
        return count.get();
    }

    private int applyTransfersInternal(
            CloudApiClient cloud,
            RootMcTreasuryService treasury,
            List<CloudApiClient.GoldTransfer> pending) {
        List<CloudApiClient.GoldTransferResult> results = new ArrayList<>();
        int applied = 0;
        // Coalesce vote_backfill notices — one line per player per batch, not N spam lines.
        java.util.Map<UUID, double[]> voteBackfillTotals = new java.util.HashMap<>();
        java.util.Map<UUID, String> voteBackfillNames = new java.util.HashMap<>();
        for (CloudApiClient.GoldTransfer transfer : pending) {
            UUID from = parseUuid(transfer.fromUuid());
            UUID to = parseUuid(transfer.toUuid());
            if (from == null || to == null) {
                results.add(new CloudApiClient.GoldTransferResult(transfer.id(), "failed", "invalid_uuid"));
                continue;
            }
            boolean ok;
            if (TOWNY_SERVER_UUID.equals(from)) {
                String recipientName = resolveRecipientName(to, transfer.toUsername());
                String reason = transfer.source() == null || transfer.source().isBlank()
                        ? "grant"
                        : transfer.source().trim();
                if (transfer.id() != null && !transfer.id().isBlank()) {
                    reason = reason + ";transfer=" + transfer.id().trim();
                }
                ok = treasury.grantToPlayer(
                        to,
                        recipientName,
                        transfer.amount(),
                        treasury.treasuryUuid(),
                        treasury.treasuryUsername(),
                        reason);
            } else {
                ok = vault.transfer(from, to, transfer.amount());
            }
            if (ok) {
                applied++;
                results.add(new CloudApiClient.GoldTransferResult(transfer.id(), "applied", null));
                if ("vote_backfill".equals(transfer.source())) {
                    voteBackfillTotals.merge(to, new double[] {transfer.amount(), 1}, (a, b) -> {
                        a[0] += b[0];
                        a[1] += b[1];
                        return a;
                    });
                    voteBackfillNames.putIfAbsent(to, resolveRecipientName(to, transfer.toUsername()));
                } else {
                    notifyTreasuryTransferApplied(transfer, to);
                }
            } else {
                String failure = TOWNY_SERVER_UUID.equals(from) ? "treasury_payout_failed" : "vault_transfer_failed";
                results.add(new CloudApiClient.GoldTransferResult(transfer.id(), "failed", failure));
            }
        }
        for (var entry : voteBackfillTotals.entrySet()) {
            UUID to = entry.getKey();
            double gold = entry.getValue()[0];
            int count = (int) entry.getValue()[1];
            String name = voteBackfillNames.getOrDefault(to, "Player");
            notifyVoteBackfillBatch(to, name, gold, count);
        }
        try {
            cloud.completeGoldTransfers(results);
        } catch (Exception ex) {
            logger.warning("Discord gold transfer complete callback failed: " + ex.getMessage());
        }
        return applied;
    }

    private void notifyVoteBackfillBatch(UUID to, String name, double gold, int count) {
        if (gold < 0.01 || count < 1) {
            return;
        }
        Player player = Bukkit.getPlayer(to);
        if (player != null) {
            com.rootrecord.minecraft.common.ChatUi.entry(
                    player,
                    "Vote",
                    "+" + formatGold(gold) + " G · " + count + " backfill",
                    "done");
        }
        RootMcPublicReachout reachout = ShadedServiceBridge.resolvePublicReachout(bridge.getPlugin());
        if (reachout != null) {
            reachout.recordTreasuryOutflow("vote", name, to, gold, false);
        }
    }

    private void notifyTreasuryTransferApplied(CloudApiClient.GoldTransfer transfer, UUID to) {
        UUID from = parseUuid(transfer.fromUuid());
        if (from == null || !TOWNY_SERVER_UUID.equals(from)) {
            return;
        }
        String source = transfer.source() == null ? "" : transfer.source().trim();
        Player player = Bukkit.getPlayer(to);
        String name = transfer.toUsername();
        if (name == null || name.isBlank()) {
            name = player != null ? player.getName() : "Player";
        }
        double amount = transfer.amount();
        RootMcPublicReachout reachout = ShadedServiceBridge.resolvePublicReachout(bridge.getPlugin());

        if ("discord_link".equals(source)) {
            if (player != null) {
                player.sendMessage(bridge.colorize(
                        bridge.msg("discord-link-bonus").replace("{gold}", formatGold(amount))));
            }
            if (reachout != null) {
                reachout.recordTreasuryOutflow("discord_link", name, to, amount, true);
            }
            return;
        }
        if ("discord_activity".equals(source)) {
            if (player == null || !DiscordActivityRewardSessions.markBroadcast(to)) {
                return;
            }
            if (reachout != null) {
                reachout.recordTreasuryOutflow("discord_activity", name, to, amount, true);
            } else {
                String line = bridge.msg("discord-activity-reward-broadcast")
                        .replace("{player}", name)
                        .replace("{gold}", formatGold(amount));
                Bukkit.broadcastMessage(line);
            }
            return;
        }
        if ("discord_first_message".equals(source)) {
            if (reachout != null) {
                reachout.recordTreasuryOutflow("discord_first_message", name, to, amount, true);
            }
            return;
        }
        if ("vote".equals(source) || "vote_backfill".equals(source)) {
            if (player != null) {
                com.rootrecord.minecraft.common.ChatUi.entry(
                        player,
                        "Vote",
                        "+" + formatGold(amount) + " G · Claims",
                        "done");
            }
            if (reachout != null) {
                reachout.recordTreasuryOutflow("vote", name, to, amount, false);
            }
            return;
        }
        if (reachout != null) {
            reachout.recordTreasuryOutflow("grant", name, to, amount, false);
        }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
