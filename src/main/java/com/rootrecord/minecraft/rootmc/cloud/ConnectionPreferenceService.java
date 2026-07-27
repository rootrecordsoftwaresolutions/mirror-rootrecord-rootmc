package com.rootrecord.minecraft.rootmc.cloud;

import com.rootrecord.minecraft.rootmc.RootMcPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Polls {@code /api/rootmc/connection-preference} for /rootstat status + /node.
 * Broadcasts Server Data Relay connected / fallback notices when preference flips.
 */
public final class ConnectionPreferenceService {

    private static final Pattern PREF = Pattern.compile("\"preference\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ACTIVE = Pattern.compile("\"active_provider\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern NODE_USER =
            Pattern.compile("\"node_discord_username\"\\s*:\\s*(?:\"([^\"]*)\"|null)");
    private static final Pattern CHECKED_AT =
            Pattern.compile("\"checked_at\"\\s*:\\s*\"([^\"]+)\"");
    /** Prefer Cloudflare display when preference payload is older than this. */
    private static final long STALE_PREF_MS = 15L * 60L * 1000L;

    private final RootMcPlugin plugin;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final AtomicReference<String> activeProvider = new AtomicReference<>("unknown");
    private final AtomicReference<String> nodeDiscordUsername = new AtomicReference<>("");
    /** null until first successful poll — avoids boot spam. */
    private volatile String lastAnnounced = null;
    private volatile int taskId = -1;

    public ConnectionPreferenceService(RootMcPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        refreshSafe();
    }

    public void stop() {
        if (taskId >= 0) {
            plugin.getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    /** Display label: solar | rootmc | cloudflare | unknown */
    public String activeProvider() {
        return activeProvider.get();
    }

    /** Discord username of signed-in Root-Core-Node operator when RootMC Network is active. */
    public String nodeDiscordUsername() {
        return nodeDiscordUsername.get();
    }

    /** Player-facing line when a Node is preferred. */
    public String relayConnectedMessage() {
        String user = nodeDiscordUsername();
        if (user == null || user.isBlank()) {
            return plugin.msg("data-relay-connected-anon");
        }
        return plugin.msg("data-relay-connected").replace("{username}", user);
    }

    /** Player-facing line when no Node is preferred (Cloudflare fallback). */
    public String relayFallbackMessage() {
        return plugin.msg("data-relay-fallback");
    }

    public String statusLine() {
        String p = activeProvider();
        if (isRootMcNetwork(p)) {
            return relayConnectedMessage();
        }
        if ("cloudflare".equals(p)) {
            return relayFallbackMessage();
        }
        return "&7Server Data Relay &8unknown";
    }

    private void refreshSafe() {
        try {
            refresh();
        } catch (Exception ex) {
            plugin.getLogger().log(Level.FINE, "connection-preference poll failed: " + ex.getMessage());
        }
    }

    private void refresh() throws Exception {
        String base = plugin.rootMcConfig() != null ? plugin.rootMcConfig().apiBase() : "";
        if (base == null || base.isBlank()) {
            base = "https://api-local.rootmc.net";
        }
        base = base.replaceAll("/+$", "");
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/rootmc/connection-preference"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            return;
        }
        String body = res.body() == null ? "" : res.body();
        String next = null;
        Matcher active = ACTIVE.matcher(body);
        if (active.find()) {
            next = normalizeDisplay(active.group(1));
        } else {
            Matcher pref = PREF.matcher(body);
            if (pref.find()) {
                next = normalizeDisplay(pref.group(1));
            }
        }
        if (next == null || "unknown".equals(next)) {
            return;
        }
        // Stale Solar preference (node closed / preference loop dead) → show Cloudflare failover.
        if (isRootMcNetwork(next) && isPreferenceStale(body)) {
            next = "cloudflare";
        }
        String user = "";
        Matcher nu = NODE_USER.matcher(body);
        if (nu.find() && nu.group(1) != null) {
            user = nu.group(1).trim();
        }
        if (!isRootMcNetwork(next)) {
            user = "";
        }
        nodeDiscordUsername.set(user);
        activeProvider.set(next);
        maybeAnnounce(next);
    }

    private static boolean isPreferenceStale(String body) {
        Matcher m = CHECKED_AT.matcher(body);
        if (!m.find()) {
            return false;
        }
        String raw = m.group(1);
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            long checked = java.time.Instant.parse(raw).toEpochMilli();
            return System.currentTimeMillis() - checked > STALE_PREF_MS;
        } catch (Exception ex) {
            return false;
        }
    }

    private void maybeAnnounce(String next) {
        String previous = lastAnnounced;
        if (previous == null) {
            lastAnnounced = next;
            return;
        }
        if (previous.equals(next)) {
            return;
        }
        boolean wasRoot = isRootMcNetwork(previous);
        boolean isRoot = isRootMcNetwork(next);
        if (wasRoot == isRoot) {
            lastAnnounced = next;
            return;
        }
        lastAnnounced = next;
        final boolean connected = isRoot;
        final String line = connected ? relayConnectedMessage() : relayFallbackMessage();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendMessage(plugin.colorize(line));
            }
            if (Bukkit.getOnlinePlayers().isEmpty()) {
                plugin.getLogger().info(stripLegacy(line) + " (no players online to notify)");
            }
        });
    }

    private static String stripLegacy(String colored) {
        if (colored == null) {
            return "";
        }
        return colored.replaceAll("(?i)&[0-9a-fk-or]", "");
    }

    private static boolean isRootMcNetwork(String provider) {
        return "solar".equals(provider) || "rootmc".equals(provider);
    }

    private static String normalizeDisplay(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if ("local".equals(v) || "solar".equals(v) || "rootmc".equals(v)) {
            return "solar";
        }
        if ("cloudflare".equals(v) || "cf".equals(v)) {
            return "cloudflare";
        }
        return v;
    }
}
