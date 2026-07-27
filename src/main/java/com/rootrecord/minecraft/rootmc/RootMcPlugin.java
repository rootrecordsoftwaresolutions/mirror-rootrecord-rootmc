package com.rootrecord.minecraft.rootmc;

import com.rootrecord.minecraft.rootmc.reachout.PublicReachoutService;
import com.rootrecord.minecraft.rootmc.cloud.CloudHeartbeatClient;
import com.rootrecord.minecraft.rootmc.cloud.ConnectionPreferenceService;
import com.rootrecord.minecraft.rootmc.metrics.HostMetricsMinuteTask;
import com.rootrecord.minecraft.common.RootMcPublicReachout;
import com.rootrecord.minecraft.rootmc.economy.PiglinDropListener;
import com.rootrecord.minecraft.rootmc.treasury.DeathTreasuryListener;
import com.rootrecord.minecraft.rootmc.discord.DiscordChatBridge;
import com.rootrecord.minecraft.rootmc.discord.DiscordChatConfig;
import com.rootrecord.minecraft.rootmc.discord.CrossServerChatBridge;
import com.rootrecord.minecraft.rootmc.discord.DiscordChatConfig;
import com.rootrecord.minecraft.rootmc.ingame.IngameEventBuffer;
import com.rootrecord.minecraft.rootmc.ingame.RootMcCommand;
import com.rootrecord.minecraft.rootmc.listener.PlaceholderApiHookListener;
import com.rootrecord.minecraft.common.RootMcEconomyBridge;
import com.rootrecord.minecraft.common.SystemGoldPayout;
import com.rootrecord.minecraft.rootmc.config.RootMcConfig;
import com.rootrecord.minecraft.rootmc.sync.HeartbeatTask;
import com.rootrecord.minecraft.rootmc.sync.PluginUpdateService;
import com.rootrecord.minecraft.common.RootRecordFolders;
import com.rootrecord.minecraft.common.config.RootMcDatabaseConfig;
import com.rootrecord.minecraft.common.config.RootRecordCloudConfig;
import com.rootrecord.minecraft.common.config.RootRecordYamlConfig;
import com.rootrecord.minecraft.rootstat.RootStatBridge;
import com.rootrecord.minecraft.rootstat.RootStatExpansion;
import com.rootrecord.minecraft.rootstat.cloud.CloudApiClient;
import com.rootrecord.minecraft.rootmc.command.NodeCommand;
import com.rootrecord.minecraft.rootstat.command.RootStatCommand;
import com.rootrecord.minecraft.rootstat.command.ValueCommand;
import com.rootrecord.minecraft.rootstat.config.RootStatConfig;
import com.rootrecord.minecraft.rootstat.governance.GovernancePowerCacheService;
import com.rootrecord.minecraft.rootstat.listener.PlayerSessionListener;
import com.rootrecord.minecraft.rootstat.mysql.McMMOStatsReader;
import com.rootrecord.minecraft.rootstat.mysql.MySqlPlayerStore;
import com.rootrecord.minecraft.rootstat.mysql.PlayerPlaytimeStore;
import com.rootrecord.minecraft.rootstat.mysql.ReportingStore;
import com.rootrecord.minecraft.rootstat.economy.EconomyCollector;
import com.rootrecord.minecraft.rootstat.economy.EconomySnapshot;
import com.rootrecord.minecraft.rootstat.economy.ShopSignListener;
import com.rootrecord.minecraft.rootstat.economy.shop.ShopPriceCapHooks;
import com.rootrecord.minecraft.rootstat.mysql.RootShopsStore;
import com.rootrecord.minecraft.rootstat.sync.McDayBedListener;
import com.rootrecord.minecraft.rootstat.sync.PhysicalGoldScanTask;
import com.rootrecord.minecraft.rootstat.sync.SyncTask;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.rootrecord.minecraft.common.bstats.Metrics;
import com.rootrecord.minecraft.common.bstats.RootBStats;

/**
 * Unified RootMC plugin - app heartbeat, account linking, McMMO sync, and playtime tracking.
 * Replaces the separate RootStat companion jar.
 */
public final class RootMcPlugin extends JavaPlugin implements RootStatBridge, RootMcEconomyBridge {

    private Metrics metrics;

    private static final String CONFIG_FILE = RootRecordFolders.ROOTMC_CONFIG;

    private static final Map<String, String> MESSAGE_DEFAULTS = Map.ofEntries(
            Map.entry("link-started", "&aVerification code: &f{code}&a — open &f{url}&a (expires in 15 min)"),
            Map.entry("link-app-code", "&aApp sign-in code: &e{code}&a — enter in the RootMC app &7(expires in 15 min)"),
            Map.entry("link-already", "&aYour account is linked to RootMC (&f{account}&a)."),
            Map.entry("link-not", "&eYou are not linked. Run &f/link &7to verify."),
            Map.entry("stats-link", "&7Public stats: &b{url}"),
            Map.entry("link-success-sync", "&aAccount linked! Syncing profile…"),
            Map.entry("sync-done", "&aSynced &f{count}&a player profile(s) from RootMC cloud."),
            Map.entry("sync-fail", "&cCloud sync failed: &f{error}"),
            Map.entry("no-permission", "&cYou don't have permission."),
            Map.entry("mysql-disabled", "&cMySQL is disabled in config — local cache unavailable."),
            Map.entry("config-missing", "&cRootMC cloud credentials missing — edit plugins/RootMC/cloud.yml"),
            Map.entry("shop-price-too-high", "&cShop price &f{price}&c for &f{item}&c exceeds the server cap (&f{max}&c, avg &f{avg}&c)."),
            Map.entry("shops-link", "&7Root Shops: &b{url}"),
            Map.entry("waypoint-saved", "&aWaypoint saved: &f{label}&a (&7{x}&a, &7{y}&a, &7{z}&a)."),
            Map.entry("note-saved", "&aNote saved at your location."),
            Map.entry("ingame-not-linked", "&eLink your account first: &f/link"),
            Map.entry("vault-claimed", "&aClaimed &f{count}&a vault item(s)."),
            Map.entry("discord-activity-reward-broadcast", "&7{player} was active on discord within the last 12 hours and earned &f{gold}g"),
            Map.entry("discord-link-bonus", "&aDiscord link bonus: &f+{gold} G&a from the server treasury."),
            Map.entry("discord-linked-welcome", "&aDiscord linked! &7Your &f100 G&7 link bonus is being sent from the server treasury."),
            Map.entry("reachout-default", "&7{player} received &f{gold} G&7 from the server treasury."),
            Map.entry("reachout-discord-activity", "&7{player} was active on Discord within the last 12 hours and earned &f{gold} G&7."),
            Map.entry("reachout-discord-link", "&7{player} linked Discord and received &f{gold} G&7 from the treasury."),
            Map.entry("reachout-discord-first-message", "&7{player} sent their first Discord message and earned &f{gold} G&7."),
            Map.entry("value-line", "&7{item}&7 — &f{each}&7 each · &f{stack}&7 per stack (&7{size}&7) · &7{samples}&7 samples"),
            Map.entry("value-not-found", "&eNo market price for &f{item}&e. Try &fdiamond&7 or &foak_log&e."),
            Map.entry("value-hand-none", "&7In hand: &8(empty) &7— hold an item or use &f/value <item>"),
            Map.entry("value-hand", "&7In hand: &f{item}&7 ×{qty} — &f{each}&7 each · &f{stack}&7 per stack (&7{size}&7)"),
            Map.entry("value-carry-total", "&7Carried market value: &a{total}&7 (&f{stacks}&7 priced items, &f{items}&7 types)"),
            Map.entry("value-carry-line", "&8  &7{item}&7 ×{qty} — &f{each}&7 each → &f{total}"),
            Map.entry("value-carry-more", "&8  &7…and {count} more types (&f{total}&7)"),
            Map.entry("value-carry-empty", "&7No priced items in your inventory."),
            Map.entry("governance-power-changed", "&7Vote &8» &f{previous}% → {current}%"),
            Map.entry("data-relay-connected", "&aServer Data Relay connected to &f{username}&a's Node."),
            Map.entry("data-relay-connected-anon", "&aServer Data Relay connected to a Node."),
            Map.entry("data-relay-fallback", "&eNo nodes are running, fallback servers Connected"),
            Map.entry("hq-node-connected", "&aServer Data Relay connected to &f{username}&a's Node."),
            Map.entry("hq-node-disconnected", "&eNo nodes are running, fallback servers Connected"));

    private RootRecordYamlConfig yamlConfig;
    private RootMcConfig rootMcConfig;
    private RootStatConfig rootStatConfig;
    private CloudHeartbeatClient heartbeatClient;
    private CloudApiClient cloudApi;
    private MySqlPlayerStore playerStore;
    private PlayerPlaytimeStore playtimeStore;
    private McMMOStatsReader mcmmoReader;
    private SyncTask syncTask;
    private PhysicalGoldScanTask physicalGoldScanTask;
    private EconomyCollector economyCollector;
    private RootShopsStore rootShopsStore;
    private ReportingStore reportingStore;
    private HostMetricsMinuteTask hostMetricsMinuteTask;
    private HeartbeatTask heartbeatTask;
    private PluginUpdateService updateService;
    private IngameEventBuffer ingameEvents;
    private DiscordChatBridge discordChatBridge;
    private DiscordChatConfig discordChatConfig;
    private CrossServerChatBridge crossServerChatBridge;
    private PublicReachoutService publicReachoutService;
    private GovernancePowerCacheService governancePowerCache;
    private ConnectionPreferenceService connectionPreference;

    @Override
    public void onEnable() {
        metrics = RootBStats.start(this);
        RootRecordCloudConfig.ensureDefaults(this);
        RootMcDatabaseConfig.ensureDefaults(this);
        yamlConfig = new RootRecordYamlConfig(this, CONFIG_FILE, CONFIG_FILE);
        yamlConfig.load();
        reloadLocalConfig();

        if (!rootStatConfig.hasServerCredentials()) {
            getLogger().warning(
                    "Server credentials missing — set plugins/RootMC/cloud.yml "
                            + "(register server in plugins/RootMC/cloud.yml)");
        }

        initMysql();

        cloudApi = new CloudApiClient(rootStatConfig);
        governancePowerCache = new GovernancePowerCacheService(this);
        heartbeatClient = new CloudHeartbeatClient(rootMcConfig, getDescription().getVersion());
        updateService = new PluginUpdateService(this);
        economyCollector = new EconomyCollector(this);
        ingameEvents = new IngameEventBuffer(yamlConfig.config().getInt("ingame.max-pending-events", 64));
        syncTask = new SyncTask(this);
        syncTask.start();
        physicalGoldScanTask = new PhysicalGoldScanTask(this);
        physicalGoldScanTask.start();
        heartbeatTask = new HeartbeatTask(this);
        heartbeatTask.start();
        getServer().getScheduler().runTaskAsynchronously(this, heartbeatTask::runSafe);
        hostMetricsMinuteTask = new HostMetricsMinuteTask(this, reportingStore);
        hostMetricsMinuteTask.start();

        registerCommands();
        getServer().getPluginManager().registerEvents(new PlayerSessionListener(this), this);
        getServer().getPluginManager().registerEvents(new ShopSignListener(this), this);
        getServer().getPluginManager().registerEvents(new DeathTreasuryListener(this), this);
        getServer().getPluginManager().registerEvents(new PiglinDropListener(this), this);
        getServer().getPluginManager().registerEvents(new McDayBedListener(this), this);
        new ShopPriceCapHooks(this).register();

        getServer().getPluginManager().registerEvents(new PlaceholderApiHookListener(this), this);

        registerPlaceholderExpansionIfPresent();
        getServer().getScheduler().runTaskLater(this, this::registerPlaceholderExpansionIfPresent, 40L);
        getServer().getScheduler().runTaskLater(this, this::registerPlaceholderExpansionIfPresent, 100L);

        economyCollector.shopListingService().logDetectedProviders();
        if (rootShopsStore != null && !rootStatConfig.isEconomyLegacyBulkSync()) {
            getServer().getScheduler().runTaskLater(this, this::backfillShopListingsToMysql, 200L);
        }
        publicReachoutService = new PublicReachoutService(this);
        connectionPreference = new ConnectionPreferenceService(this);
        connectionPreference.start();
        getServer().getServicesManager().register(
                RootMcPublicReachout.class,
                publicReachoutService,
                this,
                org.bukkit.plugin.ServicePriority.Normal);
        getLogger().info("RootMC enabled — RootMC linking, McMMO, economy, in-game capture, app sync.");
    }

    @Override
    public void onDisable() {
        RootBStats.shutdown(metrics);
        if (connectionPreference != null) {
            connectionPreference.stop();
            connectionPreference = null;
        }
        if (discordChatBridge != null) {
            discordChatBridge.stop();
            discordChatBridge = null;
        }
        if (crossServerChatBridge != null) {
            crossServerChatBridge.stop();
            crossServerChatBridge = null;
        }
        if (syncTask != null) {
            syncTask.stop();
        }
        if (physicalGoldScanTask != null) {
            physicalGoldScanTask.stop();
        }
        if (heartbeatTask != null) {
            heartbeatTask.stop();
        }
        if (hostMetricsMinuteTask != null) {
            hostMetricsMinuteTask.stop();
        }
        if (playerStore != null) {
            playerStore.close();
        }
        mcmmoReader = null;
        playtimeStore = null;
    }

    private void initMysql() {
        if (!rootStatConfig.isMysqlEnabled()) {
            return;
        }
        try {
            playerStore = new MySqlPlayerStore(rootStatConfig);
            playerStore.initSchema();

            playtimeStore = new PlayerPlaytimeStore(rootStatConfig, connectionSupplier());
            playtimeStore.initSchema();

            if (rootStatConfig.isMcmmoEnabled()) {
                mcmmoReader = new McMMOStatsReader(rootStatConfig, connectionSupplier());
                getLogger().info("McMMO reader enabled (prefix: " + rootStatConfig.mcmmoTablePrefix() + ").");
            }

            rootShopsStore = new RootShopsStore(rootStatConfig, () -> playerStore.openConnection());
            rootShopsStore.initSchema();

            reportingStore = new ReportingStore(rootStatConfig, connectionSupplier());
            reportingStore.initSchema();

            getLogger().info("MySQL ready (" + rootStatConfig.mysqlDatabase() + ").");
        } catch (Exception ex) {
            getLogger().severe("MySQL init failed: " + ex.getMessage());
            playerStore = null;
            playtimeStore = null;
            mcmmoReader = null;
            rootShopsStore = null;
            reportingStore = null;
        }
    }

    public PublicReachoutService publicReachout() {
        return publicReachoutService;
    }

    public DiscordChatBridge discordChatBridge() {
        return discordChatBridge;
    }

    public String rawMsg(String key) {
        String body = yamlConfig.config().getString("messages." + key);
        if (body == null || body.isBlank() || body.equals(key)) {
            body = MESSAGE_DEFAULTS.getOrDefault(key, key);
        }
        return body;
    }

    public String colorize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', raw);
    }

    private java.util.function.Supplier<java.sql.Connection> connectionSupplier() {
        return () -> {
            try {
                return playerStore.openConnection();
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        };
    }

    private void registerCommands() {
        var handler = new RootStatCommand(this);
        var valueHandler = new ValueCommand(this);
        var ingameHandler = new RootMcCommand(this, ingameEvents);
        bind("rootstat", handler, handler);
        bind("value", valueHandler, valueHandler);
        var nodeHandler = new NodeCommand(this);
        bind("node", nodeHandler, nodeHandler);
        for (String name : List.of("link", "waypoint", "note", "notes", "waypoints", "vault")) {
            bind(name, ingameHandler, ingameHandler);
        }
    }

    private void bind(String name, org.bukkit.command.CommandExecutor executor, org.bukkit.command.TabCompleter tab) {
        var cmd = getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(executor);
            cmd.setTabCompleter(tab);
        }
    }

    public IngameEventBuffer ingameEvents() {
        return ingameEvents;
    }

    public void flushIngameEventsAsync() {
        if (cloudApi == null || ingameEvents == null) {
            return;
        }
        var batch = ingameEvents.drain();
        if (batch.isEmpty()) {
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                cloudApi.syncIngameEvents(batch);
            } catch (Exception ex) {
                getLogger().warning("In-game event sync failed: " + ex.getMessage());
                batch.forEach(ingameEvents::enqueue);
            }
        });
    }

    private void backfillShopListingsToMysql() {
        if (rootShopsStore == null) {
            return;
        }
        Object exporter = com.rootrecord.minecraft.rootstat.economy.shop.RootMcShopsProvider.rootShopsExporter();
        if (exporter == null) {
            return;
        }
        Object raw = com.rootrecord.minecraft.rootstat.economy.shop.ReflectionShopSupport.invokeNoArg(
                exporter, "collectListings");
        List<?> listings = new ArrayList<>(
                com.rootrecord.minecraft.rootstat.economy.shop.ReflectionShopSupport.asCollection(raw));
        if (listings.isEmpty()) {
            return;
        }
        List<EconomySnapshot.ShopListingRow> rows = new ArrayList<>(listings.size());
        for (Object dto : listings) {
            EconomySnapshot.ShopListingRow row = mapBackfillDto(dto);
            if (row != null) {
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                rootShopsStore.backfillListings(rows);
                getLogger().info("Shop MySQL backfill: " + rows.size() + " listing(s).");
            } catch (SQLException ex) {
                getLogger().warning("Shop MySQL backfill failed: " + ex.getMessage());
            }
        });
    }

    private static EconomySnapshot.ShopListingRow mapBackfillDto(Object dto) {
        if (dto == null) {
            return null;
        }
        Object shopIdObj = invokeDto(dto, "shopId");
        Object itemKeyObj = invokeDto(dto, "itemKey");
        String shopId = shopIdObj == null ? null : String.valueOf(shopIdObj);
        String itemKey = itemKeyObj == null ? null : String.valueOf(itemKeyObj);
        if (shopId == null || shopId.isBlank() || itemKey == null || itemKey.isBlank()) {
            return null;
        }
        return new EconomySnapshot.ShopListingRow(
                shopId,
                strDto(dto, "ownerUuid"),
                strDto(dto, "ownerUsername"),
                strDto(dto, "worldName"),
                intDto(dto, "x"),
                intDto(dto, "y"),
                intDto(dto, "z"),
                itemKey,
                doubleDto(dto, "price"),
                strDto(dto, "listingType"),
                intDto(dto, "stockQuantity"));
    }

    private static Object invokeDto(Object dto, String method) {
        return com.rootrecord.minecraft.rootstat.economy.shop.ReflectionShopSupport.invokeNoArg(dto, method);
    }

    private static String strDto(Object dto, String method) {
        Object v = invokeDto(dto, method);
        return v == null ? null : String.valueOf(v);
    }

    private static int intDto(Object dto, String method) {
        Object v = invokeDto(dto, method);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static double doubleDto(Object dto, String method) {
        Object v = invokeDto(dto, method);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    public void claimVaultAsync(Player player) {
        if (cloudApi == null) {
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                var result = cloudApi.claimVaultOrders(player.getUniqueId().toString());
                getServer().getScheduler().runTask(this, () -> deliverVaultItems(player, result));
            } catch (Exception ex) {
                getServer().getScheduler().runTask(this, () ->
                        player.sendMessage(colorize("&cVault claim failed: &f" + ex.getMessage())));
            }
        });
    }

    private void deliverVaultItems(Player player, CloudApiClient.VaultClaimResult result) {
        if (result.items().isEmpty()) {
            player.sendMessage(colorize("&7No pending vault items."));
            return;
        }
        int delivered = 0;
        for (CloudApiClient.VaultClaimResult.VaultItem item : result.items()) {
            Material mat = Material.matchMaterial(item.itemKey());
            if (mat == null || mat.isAir()) {
                continue;
            }
            int remaining = item.quantity();
            while (remaining > 0) {
                int stack = Math.min(remaining, mat.getMaxStackSize());
                ItemStack give = new ItemStack(mat, stack);
                SystemGoldPayout.mark(give);
                var leftover = player.getInventory().addItem(give);
                if (!leftover.isEmpty()) {
                    leftover.values().forEach(stackItem ->
                            player.getWorld().dropItemNaturally(player.getLocation(), stackItem));
                }
                remaining -= stack;
                delivered += stack;
            }
        }
        player.sendMessage(msg("vault-claimed").replace("{count}", String.valueOf(delivered)));
    }

    @Override
    public double averagePrice(String itemKey) {
        double live = liveInStockMedian(itemKey);
        if (live > 0) {
            return live;
        }
        if (economyCollector == null) {
            return 0;
        }
        return economyCollector.priceRegistry().averagePrice(itemKey);
    }

    private static double liveInStockMedian(String itemKey) {
        Object exporter = com.rootrecord.minecraft.rootstat.economy.shop.RootMcShopsProvider.rootShopsExporter();
        if (exporter == null) {
            return 0;
        }
        try {
            Object median = exporter.getClass().getMethod("medianInStockSellPrice", String.class)
                    .invoke(exporter, itemKey);
            return median instanceof Number n ? n.doubleValue() : 0;
        } catch (ReflectiveOperationException ex) {
            return 0;
        }
    }

    @Override
    public double maxAllowedPrice(String itemKey, double capPercentOverAvg) {
        if (economyCollector == null) {
            return Double.MAX_VALUE;
        }
        return economyCollector.priceRegistry().maxAllowedPrice(itemKey, capPercentOverAvg);
    }

    public void reloadLocalConfig() {
        if (yamlConfig == null) {
            yamlConfig = new RootRecordYamlConfig(this, CONFIG_FILE, CONFIG_FILE);
        }
        yamlConfig.reload();
        rootMcConfig = RootMcConfig.from(this, yamlConfig.config());
        rootStatConfig = RootStatConfig.from(this, yamlConfig.config());
        discordChatConfig = DiscordChatConfig.from(this, yamlConfig.config());
        if (cloudApi != null) {
            cloudApi.updateConfig(rootStatConfig);
        }
        if (heartbeatClient != null) {
            heartbeatClient.updateConfig(rootMcConfig, getDescription().getVersion());
        }
        if (economyCollector != null) {
            economyCollector = new EconomyCollector(this);
        }
        if (publicReachoutService != null) {
            publicReachoutService.reloadFromConfig();
        }
        startDiscordChatBridge();
        startCrossServerChatBridge();
    }

    private void startDiscordChatBridge() {
        if (discordChatBridge != null) {
            discordChatBridge.stop();
            discordChatBridge = null;
        }
        if (discordChatConfig == null) {
            discordChatConfig = DiscordChatConfig.from(this, yamlConfig.config());
        }
        if (!discordChatConfig.enabled()) {
            return;
        }
        discordChatBridge = new DiscordChatBridge(this, discordChatConfig);
        discordChatBridge.start();
    }

    private void startCrossServerChatBridge() {
        if (crossServerChatBridge != null) {
            crossServerChatBridge.stop(false);
            crossServerChatBridge = null;
        }
        crossServerChatBridge = new CrossServerChatBridge(this);
        crossServerChatBridge.reloadAndStart(yamlConfig.config());
    }

    @Override
    public void reloadRootStatConfig() {
        reloadLocalConfig();
        if (syncTask != null) {
            syncTask.start();
        }
        if (physicalGoldScanTask != null) {
            physicalGoldScanTask.start();
        }
        if (heartbeatTask != null) {
            heartbeatTask.start();
        }
    }

    public RootMcConfig rootMcConfig() {
        return rootMcConfig;
    }

    public org.bukkit.configuration.file.FileConfiguration rootMcYaml() {
        return yamlConfig != null ? yamlConfig.config() : getConfig();
    }

    public CloudHeartbeatClient heartbeatClient() {
        return heartbeatClient;
    }

    public HeartbeatTask heartbeatTask() {
        return heartbeatTask;
    }

    public PluginUpdateService updates() {
        return updateService;
    }

    @Override
    public Plugin getPlugin() {
        return this;
    }

    @Override
    public RootStatConfig config() {
        return rootStatConfig;
    }

    @Override
    public CloudApiClient cloud() {
        return cloudApi;
    }

    @Override
    public MySqlPlayerStore players() {
        return playerStore;
    }

    @Override
    public McMMOStatsReader mcmmo() {
        return mcmmoReader;
    }

    @Override
    public PlayerPlaytimeStore playtime() {
        return playtimeStore;
    }

    @Override
    public SyncTask syncTask() {
        return syncTask;
    }

    /** Called by RootMC-Shops after a sale so web stock counts refresh without waiting for the 5-min cron. */
    public void requestShopListingSync(String shopId, boolean deleted, String itemKey, String previousItemKey) {
        if (syncTask != null) {
            syncTask.requestShopListingSync(shopId, deleted, itemKey, previousItemKey);
        }
    }

    /** @deprecated use {@link #requestShopListingSync} */
    public void requestEconomySync() {
        if (syncTask != null) {
            syncTask.requestEconomySync();
        }
    }

    @Override
    public EconomyCollector economy() {
        return economyCollector;
    }

    @Override
    public RootShopsStore rootShops() {
        return rootShopsStore;
    }

    @Override
    public ReportingStore reporting() {
        return reportingStore;
    }

    /** Reflection entry point used by Root-ItemInfo without opening a second MySQL pool. */
    public void persistItemCensus(
            long scannedAt,
            String scanNote,
            Map<String, Long> counts,
            Map<String, Double> averages,
            Map<String, Double> mintPegTotals,
            double goldMintPegG)
            throws SQLException {
        if (reportingStore == null) {
            throw new SQLException("RootMC reporting MySQL is unavailable");
        }
        reportingStore.replaceItemCensus(
                rootStatConfig.serverId(),
                scannedAt,
                scanNote,
                counts,
                averages,
                mintPegTotals,
                goldMintPegG);
    }

    @Override
    public GovernancePowerCacheService governancePower() {
        return governancePowerCache;
    }

    public ConnectionPreferenceService connectionPreference() {
        return connectionPreference;
    }

    @Override
    public String msg(String key) {
        String prefix = yamlConfig.config().getString("messages.prefix", "");
        String body = yamlConfig.config().getString("messages." + key);
        if (body == null || body.isBlank() || body.equals(key)) {
            body = MESSAGE_DEFAULTS.getOrDefault(key, key);
        }
        return colorize(prefix + body);
    }

    private boolean placeholderExpansionRegistered;

    public void registerPlaceholderExpansionIfPresent() {
        if (placeholderExpansionRegistered) {
            return;
        }
        var papi = getServer().getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null || !papi.isEnabled()) {
            return;
        }
        try {
            Class.forName(
                    "me.clip.placeholderapi.expansion.PlaceholderExpansion",
                    false,
                    papi.getClass().getClassLoader());
            boolean rootmc = new RootStatExpansion(this, "rootmc").register();
            boolean rootstat = new RootStatExpansion(this, "rootstat").register();
            if (rootmc || rootstat) {
                placeholderExpansionRegistered = true;
                getLogger().info("PlaceholderAPI expansions registered (rootmc, rootstat legacy).");
            } else {
                getLogger().warning("PlaceholderAPI expansion register() returned false for rootmc/rootstat.");
            }
        } catch (Throwable ex) {
            getLogger().warning("PlaceholderAPI expansion failed: " + ex.getMessage());
        }
    }
}
