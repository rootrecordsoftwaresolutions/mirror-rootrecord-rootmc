package com.rootrecord.minecraft.rootstat.config;

import com.rootrecord.minecraft.common.McDayClock;
import com.rootrecord.minecraft.common.config.RootRecordCloudConfig;
import com.rootrecord.minecraft.common.config.RootMcDatabaseConfig;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RootStatConfig {

    private final String apiBase;
    private final String serverId;
    private final String serverSecret;
    private final int syncIntervalMinutes;
    private final boolean syncOnMcDay;
    private final long mcDayTicks;
    private final String dayWorld;
    private final String statsUrlBase;
    private final boolean mysqlEnabled;
    private final String mysqlHost;
    private final int mysqlPort;
    private final String mysqlDatabase;
    private final String mysqlUsername;
    private final String mysqlPassword;
    private final String mysqlTablePrefix;
    private final int mysqlPoolSize;
    private final String mysqlJdbcParams;
    private final boolean mcmmoEnabled;
    private final String mcmmoTablePrefix;
    private final boolean economyEnabled;
    private final boolean economyVaultEnabled;
    private final boolean economyScanSigns;
    private final boolean economyScanChests;
    private final int economyMaxChunksPerSync;
    private final boolean economyEnforcePriceCap;
    private final double economyPriceCapPercentOverAvg;
    private final String economyShopProvider;
    private final List<String> economyShopProvidersPriority;
    private final boolean economyLegacyBulkSync;
    private final boolean economyMysqlPullAuthoritative;
    private final boolean physicalGoldScanEnabled;
    private final int physicalGoldScanIntervalMinutes;
    private final int physicalGoldMaxShopsPerScan;

    private RootStatConfig(
            String apiBase,
            String serverId,
            String serverSecret,
            int syncIntervalMinutes,
            boolean syncOnMcDay,
            long mcDayTicks,
            String dayWorld,
            String statsUrlBase,
            boolean mysqlEnabled,
            String mysqlHost,
            int mysqlPort,
            String mysqlDatabase,
            String mysqlUsername,
            String mysqlPassword,
            String mysqlTablePrefix,
            int mysqlPoolSize,
            String mysqlJdbcParams,
            boolean mcmmoEnabled,
            String mcmmoTablePrefix,
            boolean economyEnabled,
            boolean economyVaultEnabled,
            boolean economyScanSigns,
            boolean economyScanChests,
            int economyMaxChunksPerSync,
            boolean economyEnforcePriceCap,
            double economyPriceCapPercentOverAvg,
            String economyShopProvider,
            List<String> economyShopProvidersPriority,
            boolean economyLegacyBulkSync,
            boolean economyMysqlPullAuthoritative,
            boolean physicalGoldScanEnabled,
            int physicalGoldScanIntervalMinutes,
            int physicalGoldMaxShopsPerScan) {
        this.apiBase = apiBase;
        this.serverId = serverId;
        this.serverSecret = serverSecret;
        this.syncIntervalMinutes = syncIntervalMinutes;
        this.syncOnMcDay = syncOnMcDay;
        this.mcDayTicks = mcDayTicks;
        this.dayWorld = dayWorld == null ? "" : dayWorld.trim();
        this.statsUrlBase = statsUrlBase;
        this.mysqlEnabled = mysqlEnabled;
        this.mysqlHost = mysqlHost;
        this.mysqlPort = mysqlPort;
        this.mysqlDatabase = mysqlDatabase;
        this.mysqlUsername = mysqlUsername;
        this.mysqlPassword = mysqlPassword;
        this.mysqlTablePrefix = mysqlTablePrefix;
        this.mysqlPoolSize = mysqlPoolSize;
        this.mysqlJdbcParams = mysqlJdbcParams;
        this.mcmmoEnabled = mcmmoEnabled;
        this.mcmmoTablePrefix = mcmmoTablePrefix;
        this.economyEnabled = economyEnabled;
        this.economyVaultEnabled = economyVaultEnabled;
        this.economyScanSigns = economyScanSigns;
        this.economyScanChests = economyScanChests;
        this.economyMaxChunksPerSync = economyMaxChunksPerSync;
        this.economyEnforcePriceCap = economyEnforcePriceCap;
        this.economyPriceCapPercentOverAvg = economyPriceCapPercentOverAvg;
        this.economyShopProvider = economyShopProvider;
        this.economyShopProvidersPriority = economyShopProvidersPriority;
        this.economyLegacyBulkSync = economyLegacyBulkSync;
        this.economyMysqlPullAuthoritative = economyMysqlPullAuthoritative;
        this.physicalGoldScanEnabled = physicalGoldScanEnabled;
        this.physicalGoldScanIntervalMinutes = physicalGoldScanIntervalMinutes;
        this.physicalGoldMaxShopsPerScan = physicalGoldMaxShopsPerScan;
    }

    public static RootStatConfig from(JavaPlugin plugin, FileConfiguration cfg) {
        RootRecordCloudConfig.CloudSettings cloud = RootRecordCloudConfig.resolve(plugin, cfg);
        RootMcDatabaseConfig.DatabaseSettings database = RootMcDatabaseConfig.resolve(plugin, cfg);
        String apiBase = cloud.apiBase().isBlank()
                ? com.rootrecord.minecraft.common.config.RootMcApiBases.defaultBase()
                : cloud.apiBase();
        applyMcDayClock(cfg);
        return new RootStatConfig(
                trimSlash(apiBase),
                cloud.serverId(),
                cloud.serverSecret(),
                Math.max(1, cfg.getInt("cloud.sync-interval-minutes", 5)),
                cfg.getBoolean("cloud.sync-on-mc-day", true),
                Math.max(1L, cfg.getLong("cloud.mc-day-ticks", 24000L)),
                cfg.getString("cloud.day-world", ""),
                trimSlash(cfg.getString("cloud.stats-url-base", "https://rootmc.net/player")),
                database.enabled(),
                database.host(),
                database.port(),
                database.database(),
                database.username(),
                database.password(),
                database.tablePrefix(),
                Math.max(1, database.poolSize()),
                database.jdbcParams(),
                cfg.getBoolean("mcmmo.enabled", true),
                cfg.getString("mcmmo.table-prefix", "mcmmo_"),
                cfg.getBoolean("economy.enabled", true),
                cfg.getBoolean("economy.vault-enabled", true),
                cfg.getBoolean("economy.scan-signs", true),
                cfg.getBoolean("economy.scan-chests", true),
                Math.max(1, cfg.getInt("economy.max-chunks-per-sync", 32)),
                cfg.getBoolean("economy.enforce-price-cap", true),
                Math.max(0, cfg.getDouble("economy.max-price-percent-over-avg", 25.0)),
                normalizeShopProvider(cfg.getString("economy.shop-provider", "auto")),
                readShopPriority(cfg),
                cfg.getBoolean("economy.legacy-bulk-sync", false),
                cfg.getBoolean("economy.mysql-pull-authoritative", false),
                cfg.getBoolean("economy.physical-gold-scan.enabled", true),
                Math.max(5, cfg.getInt("economy.physical-gold-scan.interval-minutes", 30)),
                Math.max(8, cfg.getInt("economy.physical-gold-scan.max-shops-per-scan", 48)));
    }

    private static void applyMcDayClock(FileConfiguration cfg) {
        if (org.bukkit.Bukkit.getPluginManager().isPluginEnabled("Root-Times")) {
            // Root-Times owns McDayClock.configure
            return;
        }
        var section = cfg.getConfigurationSection("minecraft-day");
        if (section == null) {
            section = cfg.getConfigurationSection("cloud.minecraft-day");
        }
        boolean active = section != null && section.getBoolean("enabled", false);
        String timezone = section == null ? "Pacific/Honolulu" : section.getString("timezone", "Pacific/Honolulu");
        int lengthMinutes = section == null ? 30 : section.getInt("length-minutes", 30);
        int middayMinute = section == null ? 0 : section.getInt("midday-minute", 0);
        int midnightMinute = section == null ? 15 : section.getInt("midnight-minute", 15);
        long dayIdBase = 0L;
        if (section != null) {
            String raw = section.getString("day-id-base", "0");
            if (raw != null) {
                String v = raw.trim();
                if (!v.isEmpty() && !"0".equals(v) && !"auto".equalsIgnoreCase(v) && !"now".equalsIgnoreCase(v)) {
                    try {
                        dayIdBase = Math.max(0L, Long.parseLong(v));
                    } catch (NumberFormatException ignored) {
                        dayIdBase = 0L;
                    }
                }
            }
        }
        McDayClock.configure(active, timezone, lengthMinutes, middayMinute, midnightMinute, dayIdBase);
    }

    private static String normalizeShopProvider(String raw) {
        if (raw == null || raw.isBlank()) {
            return "auto";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> readShopPriority(FileConfiguration cfg) {
        List<String> fromList = cfg.getStringList("economy.shop-providers-priority");
        if (fromList != null && !fromList.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (String entry : fromList) {
                if (entry != null && !entry.isBlank()) {
                    out.add(entry.trim().toLowerCase(Locale.ROOT));
                }
            }
            if (!out.isEmpty()) {
                return List.copyOf(out);
            }
        }
        return List.of("quickshop", "chestshop", "sign");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://api.rootmc.net";
        }
        return value.replaceAll("/+$", "");
    }

    public boolean hasServerCredentials() {
        return !serverId.isBlank() && !serverSecret.isBlank();
    }

    public String apiBase() {
        return apiBase;
    }

    public String serverId() {
        return serverId;
    }

    public String serverSecret() {
        return serverSecret;
    }

    public int syncIntervalMinutes() {
        return syncIntervalMinutes;
    }

    /** When true, full cloud economy sync runs once per Minecraft day instead of sync-interval-minutes. */
    public boolean syncOnMcDay() {
        return syncOnMcDay;
    }

    public long mcDayTicks() {
        return mcDayTicks;
    }

    public String dayWorld() {
        return dayWorld;
    }

    public String statsUrlBase() {
        return statsUrlBase;
    }

    public boolean isMysqlEnabled() {
        return mysqlEnabled;
    }

    public String mysqlHost() {
        return mysqlHost;
    }

    public int mysqlPort() {
        return mysqlPort;
    }

    public String mysqlDatabase() {
        return mysqlDatabase;
    }

    public String mysqlUsername() {
        return mysqlUsername;
    }

    public String mysqlPassword() {
        return mysqlPassword;
    }

    public String mysqlTablePrefix() {
        return mysqlTablePrefix;
    }

    public int mysqlPoolSize() {
        return mysqlPoolSize;
    }

    public String mysqlJdbcParams() {
        return mysqlJdbcParams;
    }

    public String playersTable() {
        return mysqlTablePrefix + "rootstat_players";
    }

    public String playtimeTable() {
        return mysqlTablePrefix + "playtime";
    }

    public String playtimeMonthlyTable() {
        return mysqlTablePrefix + "playtime_monthly";
    }

    public String jdbcUrl() {
        String params = mysqlJdbcParams == null || mysqlJdbcParams.isBlank() ? "" : "?" + mysqlJdbcParams;
        return "jdbc:mysql://" + mysqlHost + ":" + mysqlPort + "/" + mysqlDatabase + params;
    }

    public boolean isMcmmoEnabled() {
        return mcmmoEnabled;
    }

    public String mcmmoTablePrefix() {
        return mcmmoTablePrefix == null ? "mcmmo_" : mcmmoTablePrefix;
    }

    public boolean isEconomyEnabled() {
        return economyEnabled;
    }

    public boolean isEconomyVaultEnabled() {
        return economyVaultEnabled;
    }

    public boolean isEconomyScanSigns() {
        return economyScanSigns;
    }

    public boolean isEconomyScanChests() {
        return economyScanChests;
    }

    public int economyMaxChunksPerSync() {
        return economyMaxChunksPerSync;
    }

    public boolean isEconomyEnforcePriceCap() {
        return economyEnforcePriceCap;
    }

    public double economyPriceCapPercentOverAvg() {
        return economyPriceCapPercentOverAvg;
    }

    public String shopsUrlBase() {
        return trimSlash(statsUrlBase()).replace("/realm/player", "/realm/shops");
    }

    public String economyShopProvider() {
        return economyShopProvider;
    }

    public List<String> economyShopProvidersPriority() {
        return economyShopProvidersPriority;
    }

    /** Full chest-scan economy snapshot push (legacy). Default false — use incremental MySQL + per-shop D1 sync. */
    public boolean isEconomyLegacyBulkSync() {
        return economyLegacyBulkSync;
    }

    /** When true, Worker cron pulls balances/playtime/treasury from MySQL; game server skips those cloud fields. */
    public boolean isEconomyMysqlPullAuthoritative() {
        return economyMysqlPullAuthoritative;
    }

    /** Periodic mint-peg scan of inventory, ender chest, shop stock, and loaded containers. */
    public boolean isPhysicalGoldScanEnabled() {
        return physicalGoldScanEnabled;
    }

    public int physicalGoldScanIntervalMinutes() {
        return physicalGoldScanIntervalMinutes;
    }

    public int physicalGoldMaxShopsPerScan() {
        return physicalGoldMaxShopsPerScan;
    }
}
