package com.rootrecord.minecraft.rootmc.config;

import com.rootrecord.minecraft.common.config.RootRecordCloudConfig;
import com.rootrecord.minecraft.common.RootMcMapUrls;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class RootMcConfig {

    private final String apiBase;
    private final String serverId;
    private final String serverSecret;
    private final int heartbeatIntervalMinutes;
    private final String serverAddress;
    private final String defaultWorldName;
    private final String gameVersion;
    private final String mapUrl;

    private RootMcConfig(
            String apiBase,
            String serverId,
            String serverSecret,
            int heartbeatIntervalMinutes,
            String serverAddress,
            String defaultWorldName,
            String gameVersion,
            String mapUrl) {
        this.apiBase = apiBase;
        this.serverId = serverId;
        this.serverSecret = serverSecret;
        this.heartbeatIntervalMinutes = heartbeatIntervalMinutes;
        this.serverAddress = serverAddress;
        this.defaultWorldName = defaultWorldName;
        this.gameVersion = gameVersion;
        this.mapUrl = mapUrl;
    }

    public static RootMcConfig from(JavaPlugin plugin, FileConfiguration cfg) {
        RootRecordCloudConfig.CloudSettings cloud = RootRecordCloudConfig.resolve(plugin, cfg);
        String apiBase = cloud.apiBase().isBlank() ? "https://api.rootmc.net" : cloud.apiBase();
        return new RootMcConfig(
                trimSlash(apiBase),
                cloud.serverId(),
                cloud.serverSecret(),
                Math.max(1, cfg.getInt("cloud.heartbeat-interval-minutes", 5)),
                nullToEmpty(cfg.getString("server.address", "")),
                nullToEmpty(cfg.getString("server.default-world-name", "RootMC")),
                nullToEmpty(cfg.getString("server.game-version", "26.2")),
                resolveMapUrl(cfg));
    }

    private static String resolveMapUrl(FileConfiguration cfg) {
        String configured = nullToEmpty(cfg.getString("server.map-url"));
        return configured.isBlank() ? RootMcMapUrls.DEFAULT_MAP_URL : configured;
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

    public int heartbeatIntervalMinutes() {
        return heartbeatIntervalMinutes;
    }

    public String serverAddress() {
        return serverAddress;
    }

    public String defaultWorldName() {
        return defaultWorldName;
    }

    public String gameVersion() {
        return gameVersion;
    }

    public String mapUrl() {
        return mapUrl;
    }
}
