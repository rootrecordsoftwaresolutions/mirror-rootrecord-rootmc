package com.rootrecord.minecraft.rootmc.cloud;

import com.rootrecord.minecraft.rootmc.config.RootMcConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

public final class CloudHostMetricsClient {

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private RootMcConfig config;

    public CloudHostMetricsClient(RootMcConfig config) {
        this.config = config;
    }

    public void updateConfig(RootMcConfig config) {
        this.config = config;
    }

    public void postMinute(
            Instant minuteStart,
            double cpuAvgPct,
            double ramAvgPct,
            double diskUsedPct,
            double tpsAvg,
            int sampleCount)
            throws IOException, InterruptedException {
        String minuteTs = minuteStart.toString();
        String body =
                "{"
                        + "\"minute_ts\":\"" + escape(minuteTs) + "\","
                        + "\"cpu_avg_pct\":" + round(cpuAvgPct) + ","
                        + "\"ram_avg_pct\":" + round(ramAvgPct) + ","
                        + "\"disk_used_pct\":" + round(diskUsedPct) + ","
                        + "\"tps_avg\":" + round(tpsAvg) + ","
                        + "\"sample_count\":" + Math.max(1, sampleCount)
                        + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.apiBase() + "/api/rootmc/host-metrics/minute"))
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
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
