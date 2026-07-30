package com.rootrecord.minecraft.rootmc.discord;

import com.rootrecord.minecraft.common.RootRecordFolders;
import com.rootrecord.minecraft.rootmc.RootMcPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Relays global chat between Gen1 and Gen2 via api2 {@code /api/rootmc/cross-chat},
 * and announces when the peer generation comes online / goes offline.
 */
public final class CrossServerChatBridge implements Listener {

    private static final Pattern COLOR_CODES = Pattern.compile("(?i)[§&][0-9a-fk-or]");
    private static final Pattern MSG_OBJ = Pattern.compile(
            "\\{\\s*\"id\"\\s*:\\s*(\\d+)\\s*,\\s*\"tag\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"username\"\\s*:\\s*\"([^\"]*)\"[\\s\\S]*?\"message\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern NEWEST_ID = Pattern.compile("\"newest_id\"\\s*:\\s*(\\d+)");
    private static final Pattern PEER_OBJ = Pattern.compile(
            "\\{\\s*\"tag\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"status\"\\s*:\\s*\"([^\"]*)\"");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final RootMcPlugin plugin;
    private final ConcurrentLinkedQueue<Outbound> outbound = new ConcurrentLinkedQueue<>();
    private final File stateFile;
    private final Map<String, String> peerStatus = new HashMap<>();

    private boolean enabled;
    private String tag = "C";
    private String apiBase = "";
    private String secret = "";
    private String transport = "peer";
    private PeerMysqlCrossChat peerChat;
    private long pollIntervalSeconds = 2L;
    private long heartbeatSeconds = 10L;
    private String format = "&8[&b{tag}&8] &f{user}&7: &f{message}";
    private String peerOnlineFormat = "&a{label} is back online, use /{cmd} to join.";
    private String peerOfflineFormat = "&8[&b{tag}&8] &e{label} is going offline.";
    private String joinFormat = "&e{player} joined this world.";
    private boolean customizeJoin = true;

    private BukkitTask pollTask;
    private BukkitTask flushTask;
    private BukkitTask heartbeatTask;
    private long lastId;
    private boolean chatPrimed;
    private boolean presencePrimed;

    public CrossServerChatBridge(RootMcPlugin plugin) {
        this.plugin = plugin;
        this.stateFile = new File(RootRecordFolders.dir(plugin), "cross-server-chat-state.yml");
    }

    public void reloadAndStart(FileConfiguration cfg) {
        stop(false);
        enabled = cfg.getBoolean("cross-server-chat.enabled", false);
        tag = normalizeTag(cfg.getString("cross-server-chat.tag", "C"));
        transport = cfg.getString("cross-server-chat.transport", "peer");
        if (transport == null || transport.isBlank()) {
            transport = "peer";
        }
        transport = transport.trim().toLowerCase(Locale.ROOT);
        apiBase = trimSlash(cfg.getString("cross-server-chat.api-base", ""));
        if (apiBase.isBlank() && plugin.config() != null) {
            apiBase = trimSlash(plugin.config().apiBase());
        }
        secret = "";
        File cloudFile = new File(RootRecordFolders.dir(plugin), "cloud.yml");
        if (cloudFile.isFile()) {
            YamlConfiguration cloud = YamlConfiguration.loadConfiguration(cloudFile);
            String fromCloud = cloud.getString("cross-server-chat.secret", "");
            if (fromCloud != null && !fromCloud.isBlank()) {
                secret = fromCloud.trim();
            }
            String baseFromCloud = cloud.getString("cross-server-chat.api-base", "");
            if (baseFromCloud != null && !baseFromCloud.isBlank()) {
                apiBase = trimSlash(baseFromCloud);
            }
        }
        String fromRoot = cfg.getString("cross-server-chat.secret", "");
        if (secret.isBlank() && fromRoot != null && !fromRoot.isBlank()) {
            secret = fromRoot.trim();
        }
        pollIntervalSeconds = Math.max(1L, cfg.getLong("cross-server-chat.poll-interval-seconds", 2L));
        heartbeatSeconds = Math.max(5L, cfg.getLong("cross-server-chat.heartbeat-seconds", 10L));
        format = cfg.getString(
                "cross-server-chat.format",
                "&8[&b{tag}&8] &f{user}&7: &f{message}");
        peerOnlineFormat = cfg.getString(
                "cross-server-chat.peer-online",
                "&a{label} is back online, use /{cmd} to join.");
        peerOfflineFormat = cfg.getString(
                "cross-server-chat.peer-offline",
                "&8[&b{tag}&8] &e{label} is going offline.");
        joinFormat = cfg.getString("messages.join", "&e{player} joined this world.");
        customizeJoin = cfg.getBoolean("messages.customize-join", true);
        if (!enabled) {
            // Still customize local join message even if relay is off.
            if (customizeJoin) {
                Bukkit.getPluginManager().registerEvents(this, plugin);
            }
            return;
        }
        peerChat = null;
        if ("peer".equals(transport)) {
            peerChat = new PeerMysqlCrossChat(plugin);
            loadState();
            if (!peerChat.configure(cfg, tag, lastId)) {
                plugin.getLogger().warning(
                        "cross-server-chat transport=peer failed (need database.yml + rootmc-official peer) — falling back to cloud.");
                peerChat = null;
                transport = "cloud";
            }
        }
        if ("cloud".equals(transport) && (apiBase.isBlank() || secret.isBlank() || tag.isBlank())) {
            plugin.getLogger().warning("cross-server-chat enabled but api-base/secret/tag missing — disabled.");
            enabled = false;
            if (customizeJoin) {
                Bukkit.getPluginManager().registerEvents(this, plugin);
            }
            return;
        }
        loadState();
        if (peerChat != null) {
            peerChat.setLastId(lastId);
        }
        chatPrimed = false;
        presencePrimed = false;
        peerStatus.clear();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Prime cursor immediately (even with 0 players) so a cold last-id cannot dump history.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::primeChatSafe);
        long pollTicks = pollIntervalSeconds * 20L;
        pollTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, this::pollSafe, pollTicks, pollTicks);
        flushTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, this::flushSafe, 10L, 10L);
        long hbTicks = heartbeatSeconds * 20L;
        heartbeatTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, () -> postPresenceSafe("online"), 100L, hbTicks);
        plugin.getLogger().info(
                "Cross-server chat enabled as [" + tag + "] transport=" + transport
                        + ("peer".equals(transport) ? " (MySQL peer)" : " → " + apiBase));
    }

    public void stop() {
        stop(true);
    }

    /** @param announceOffline when true, posts offline presence to the peer (disable / shutdown). */
    public void stop(boolean announceOffline) {
        HandlerList.unregisterAll(this);
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
        flushSafe();
        if (announceOffline && enabled) {
            try {
                postPresence("offline");
            } catch (Exception ex) {
                plugin.getLogger().log(Level.FINE, "Cross-presence offline post failed", ex);
            }
        }
        saveState();
        enabled = false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        if (!customizeJoin) {
            return;
        }
        String raw = joinFormat.replace("{player}", event.getPlayer().getName());
        event.joinMessage(LEGACY.deserialize(plugin.colorize(raw)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!enabled) {
            return;
        }
        String message = stripColors(LEGACY.serialize(event.message()));
        if (message.isBlank() || message.startsWith("/")) {
            return;
        }
        Player player = event.getPlayer();
        outbound.add(new Outbound(player.getName(), player.getUniqueId().toString(), message));
    }

    private void flushSafe() {
        if (!enabled) {
            return;
        }
        List<Outbound> batch = new ArrayList<>();
        Outbound next;
        while ((next = outbound.poll()) != null) {
            batch.add(next);
            if (batch.size() >= 20) {
                break;
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        for (Outbound row : batch) {
            try {
                postOne(row);
            } catch (Exception ex) {
                String err = ex.getMessage() == null ? "" : ex.getMessage();
                plugin.getLogger().log(Level.WARNING, "Cross-chat post failed: " + err);
                // Permanent client errors must not requeue — that floods the console every 0.5s.
                if (isRetryableCrossChatError(err)) {
                    outbound.add(row);
                }
                break;
            }
        }
    }

    private static boolean isRetryableCrossChatError(String err) {
        if (err == null || err.isBlank()) {
            return true;
        }
        // Retry transport / 5xx / rate-limit; drop 4xx body validation failures.
        if (err.contains("HTTP 429")) {
            return true;
        }
        if (err.matches("(?s).*HTTP 5\\d\\d.*")) {
            return true;
        }
        if (err.matches("(?s).*HTTP 4\\d\\d.*")) {
            return false;
        }
        return true;
    }

    private void postOne(Outbound row) throws IOException, InterruptedException {
        if (peerChat != null) {
            try {
                peerChat.post(row.username(), row.message());
                lastId = Math.max(lastId, peerChat.lastId());
                return;
            } catch (Exception ex) {
                throw new IOException(ex.getMessage(), ex);
            }
        }
        String body = "{\"tag\":\"" + escapeJson(apiWireTag())
                + "\",\"username\":\"" + escapeJson(row.username())
                + "\",\"uuid\":\"" + escapeJson(row.uuid())
                + "\",\"message\":\"" + escapeJson(row.message()) + "\"}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/api/rootmc/cross-chat"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Cross-Chat-Secret", secret)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IOException("HTTP " + res.statusCode() + ": " + res.body());
        }
    }

    private void postPresenceSafe(String status) {
        if (!enabled) {
            return;
        }
        try {
            postPresence(status);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.FINE, "Cross-presence " + status + " failed: " + ex.getMessage());
        }
    }

    private void postPresence(String status) throws IOException, InterruptedException {
        if (peerChat != null) {
            try {
                peerChat.heartbeat(status);
                return;
            } catch (Exception ex) {
                throw new IOException(ex.getMessage(), ex);
            }
        }
        String body = "{\"tag\":\"" + escapeJson(apiWireTag())
                + "\",\"status\":\"" + escapeJson(status) + "\"}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/api/rootmc/cross-presence"))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .header("X-Cross-Chat-Secret", secret)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IOException("HTTP " + res.statusCode() + ": " + res.body());
        }
    }

    private void primeChatSafe() {
        if (!enabled || chatPrimed) {
            return;
        }
        try {
            if (peerChat != null) {
                lastId = Math.max(lastId, peerChat.seekToEnd());
                chatPrimed = true;
                saveState();
                plugin.getLogger().info(
                        "Cross-server chat primed at last-id=" + lastId + " (skipped backlog).");
                return;
            }
            // Cloud: one prime poll (API returns newest id, no message bodies when prime=1).
            pollChat();
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Cross-chat prime failed: " + ex.getMessage());
        }
    }

    private void pollSafe() {
        if (!enabled) {
            return;
        }
        try {
            pollPresence();
            // Always advance the cursor — even with 0 players. Skipping poll while empty
            // freezes last-id, then the first Claims login dumps peer backlog at once.
            pollChat();
            saveState();
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Cross-chat poll failed: " + ex.getMessage());
        }
    }

    private void pollChat() throws IOException, InterruptedException {
        if (peerChat != null) {
            try {
                // First poll after boot: jump to MAX(id) — never stream backlog in 100-row chunks.
                if (!chatPrimed) {
                    lastId = Math.max(lastId, peerChat.seekToEnd());
                    chatPrimed = true;
                    saveState();
                    plugin.getLogger().info(
                            "Cross-server chat primed at last-id=" + lastId + " (skipped backlog).");
                    return;
                }
                List<PeerMysqlCrossChat.ChatRow> rows = peerChat.poll();
                lastId = Math.max(lastId, peerChat.lastId());
                if (rows.isEmpty()) {
                    return;
                }
                // Cursor already advanced; only show lines when someone is online.
                if (plugin.getServer().getOnlinePlayers().isEmpty()) {
                    return;
                }
                Instant cutoff = Instant.now().minusSeconds(45);
                List<String> lines = new ArrayList<>();
                for (PeerMysqlCrossChat.ChatRow row : rows) {
                    if (row.at() != null && row.at().isBefore(cutoff)) {
                        continue;
                    }
                    lines.add(format
                            .replace("{tag}", displayTag(row.tag()))
                            .replace("{user}", row.username())
                            .replace("{message}", row.message()));
                }
                if (lines.isEmpty()) {
                    return;
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    for (String line : lines) {
                        Bukkit.broadcastMessage(plugin.colorize(line));
                    }
                });
            } catch (Exception ex) {
                throw new IOException(ex.getMessage(), ex);
            }
            return;
        }
        String qs = "after=" + lastId
                + "&exclude_tag=" + URLEncoder.encode(apiWireTag(), StandardCharsets.UTF_8)
                + "&limit=25"
                + (chatPrimed ? "" : "&prime=1");
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/api/rootmc/cross-chat/poll?" + qs))
                .timeout(Duration.ofSeconds(10))
                .header("X-Cross-Chat-Secret", secret)
                .GET()
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IOException("HTTP " + res.statusCode() + ": " + res.body());
        }
        String json = res.body();
        Matcher newest = NEWEST_ID.matcher(json);
        if (newest.find()) {
            lastId = Math.max(lastId, Long.parseLong(newest.group(1)));
        }
        boolean wasPrime = !chatPrimed;
        chatPrimed = true;
        if (wasPrime || plugin.getServer().getOnlinePlayers().isEmpty()) {
            // Prime (or empty server): advance cursor only — never dump bodies.
            Matcher advance = MSG_OBJ.matcher(json);
            while (advance.find()) {
                lastId = Math.max(lastId, Long.parseLong(advance.group(1)));
            }
            return;
        }
        List<String> lines = new ArrayList<>();
        Matcher msg = MSG_OBJ.matcher(json);
        while (msg.find()) {
            lastId = Math.max(lastId, Long.parseLong(msg.group(1)));
            String remoteTag = displayTag(unescapeJson(msg.group(2)));
            String user = unescapeJson(msg.group(3));
            String message = unescapeJson(msg.group(4));
            lines.add(format
                    .replace("{tag}", remoteTag)
                    .replace("{user}", user)
                    .replace("{message}", message));
        }
        if (!lines.isEmpty()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (String line : lines) {
                    Bukkit.broadcastMessage(plugin.colorize(line));
                }
            });
        }
    }

    private void pollPresence() throws IOException, InterruptedException {
        if (peerChat != null) {
            // Peer MySQL presence is best-effort via chat heartbeat rows; skip HTTP.
            presencePrimed = true;
            return;
        }
        String qs = "exclude_tag=" + URLEncoder.encode(apiWireTag(), StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/api/rootmc/cross-presence?" + qs))
                .timeout(Duration.ofSeconds(8))
                .header("X-Cross-Chat-Secret", secret)
                .GET()
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IOException("HTTP " + res.statusCode() + ": " + res.body());
        }
        List<String> announcements = new ArrayList<>();
        Matcher peer = PEER_OBJ.matcher(res.body());
        while (peer.find()) {
            String remoteTag = displayTag(unescapeJson(peer.group(1))).toUpperCase(Locale.ROOT);
            String status = unescapeJson(peer.group(2)).toLowerCase(Locale.ROOT);
            if (remoteTag.isBlank() || remoteTag.equals(tag) || remoteTag.equals(apiWireTag())) {
                continue;
            }
            if (!"online".equals(status) && !"offline".equals(status)) {
                continue;
            }
            String previous = peerStatus.put(remoteTag, status);
            if (!presencePrimed) {
                continue;
            }
            // Skip unchanged; announce first-sight only when the peer is online
            // (e.g. this host was up while the other was still booting).
            if (previous != null && previous.equals(status)) {
                continue;
            }
            if (previous == null && !"online".equals(status)) {
                continue;
            }
            String template = "online".equals(status) ? peerOnlineFormat : peerOfflineFormat;
            announcements.add(template
                    .replace("{tag}", remoteTag)
                    .replace("{label}", labelFor(remoteTag))
                    .replace("{cmd}", cmdFor(remoteTag)));
        }
        presencePrimed = true;
        if (!announcements.isEmpty()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (String line : announcements) {
                    Bukkit.broadcastMessage(plugin.colorize(line));
                }
            });
        }
    }

    /** Wire format for api2 (G1/G2). Local config still uses T/C. */
    private String apiWireTag() {
        if ("T".equalsIgnoreCase(tag) || "G1".equalsIgnoreCase(tag) || "TOWNY".equalsIgnoreCase(tag)) {
            return "G1";
        }
        if ("C".equalsIgnoreCase(tag) || "G2".equalsIgnoreCase(tag) || "CLAIMS".equalsIgnoreCase(tag)) {
            return "G2";
        }
        return tag == null ? "" : tag;
    }

    private static String displayTag(String remote) {
        if (remote == null || remote.isBlank()) {
            return "";
        }
        String t = remote.trim().toUpperCase(Locale.ROOT);
        if ("G1".equals(t) || "T".equals(t) || "TOWNY".equals(t) || "GEN1".equals(t)) {
            return "T";
        }
        if ("G2".equals(t) || "C".equals(t) || "CLAIMS".equals(t) || "GEN2".equals(t)) {
            return "C";
        }
        return t;
    }

    private static String labelFor(String tag) {
        if ("T".equalsIgnoreCase(tag) || "G1".equalsIgnoreCase(tag) || "TOWNY".equalsIgnoreCase(tag)) {
            return "Towny";
        }
        if ("C".equalsIgnoreCase(tag) || "G2".equalsIgnoreCase(tag) || "CLAIMS".equalsIgnoreCase(tag)) {
            return "Claims";
        }
        return tag;
    }

    private static String cmdFor(String tag) {
        if ("T".equalsIgnoreCase(tag) || "G1".equalsIgnoreCase(tag) || "TOWNY".equalsIgnoreCase(tag)) {
            return "goto towny";
        }
        if ("C".equalsIgnoreCase(tag) || "G2".equalsIgnoreCase(tag) || "CLAIMS".equalsIgnoreCase(tag)) {
            return "goto claims";
        }
        return tag == null ? "" : tag.toLowerCase(Locale.ROOT);
    }

    private void loadState() {
        if (!stateFile.isFile()) {
            return;
        }
        lastId = YamlConfiguration.loadConfiguration(stateFile).getLong("last-id", 0L);
    }

    private void saveState() {
        try {
            File parent = stateFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("last-id", lastId);
            yaml.save(stateFile);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.FINE, "Could not save cross-chat state", ex);
        }
    }

    private static String normalizeTag(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String t = raw.trim().toUpperCase();
        if (t.equals("1") || t.equals("GEN1") || t.equals("G1") || t.equals("TOWNY")) {
            return "T";
        }
        if (t.equals("2") || t.equals("GEN2") || t.equals("G2") || t.equals("CLAIMS")) {
            return "C";
        }
        return t;
    }

    private static String trimSlash(String value) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private static String stripColors(String value) {
        if (value == null) {
            return "";
        }
        return COLOR_CODES.matcher(value).replaceAll("").trim();
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String unescapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private record Outbound(String username, String uuid, String message) {}
}
