package com.rootrecord.minecraft.rootmc.cloud;

import com.rootrecord.minecraft.rootmc.config.RootMcConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class CloudHeartbeatClient {

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private RootMcConfig config;
    private String pluginVersion;

    public CloudHeartbeatClient(RootMcConfig config, String pluginVersion) {
        this.config = config;
        this.pluginVersion = pluginVersion;
    }

    public void updateConfig(RootMcConfig config, String pluginVersion) {
        this.config = config;
        this.pluginVersion = pluginVersion;
    }

    public String sendHeartbeat(int onlinePlayers) throws IOException, InterruptedException {
        String body =
                "{"
                        + "\"plugin_version\":\"" + escape(pluginVersion) + "\","
                        + "\"server_address\":\"" + escape(config.serverAddress()) + "\","
                        + "\"default_world_name\":\"" + escape(config.defaultWorldName()) + "\","
                        + "\"game_version\":\"" + escape(config.gameVersion()) + "\","
                        + "\"online_players\":" + Math.max(0, onlinePlayers)
                        + (config.mapUrl().isBlank()
                                ? ""
                                : ",\"map_url\":\"" + escape(config.mapUrl()) + "\"")
                        + "}";

        // Gen2 (api2) prefers /api/v2/realm/heartbeat; Gen1 keeps /api/rootmc/server/heartbeat.
        // api2 also accepts the Gen1 path via a Worker shim for older jars.
        String path = heartbeatPath(config.apiBase());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.apiBase() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-RootStat-Server-Id", config.serverId())
                .header("X-RootStat-Server-Secret", config.serverSecret())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    /** api2.rootmc.net → v2 heartbeat; everything else → Gen1 RootMC heartbeat. */
    static String heartbeatPath(String apiBase) {
        String base = apiBase == null ? "" : apiBase.toLowerCase();
        if (base.contains("api2.rootmc.net") || base.contains("api2.")) {
            return "/api/v2/realm/heartbeat";
        }
        return "/api/rootmc/server/heartbeat";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
