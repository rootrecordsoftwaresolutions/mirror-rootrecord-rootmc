package com.rootrecord.minecraft.rootmc.reachout;

import com.rootrecord.minecraft.common.RootMcPublicReachout;
import com.rootrecord.minecraft.common.RootMcTreasuryService;
import com.rootrecord.minecraft.common.ShadedServiceBridge;
import com.rootrecord.minecraft.rootmc.RootMcPlugin;
import com.rootrecord.minecraft.rootmc.discord.DiscordChatBridge;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Hourly treasury reachout aggregates + optional in-game / Discord #ingame-chat relay. */
public final class PublicReachoutService implements RootMcPublicReachout {

    private static final ZoneId HST = ZoneId.of("Pacific/Honolulu");
    private static final Pattern COLOR_CODES = Pattern.compile("(?i)[\u00A7&][0-9a-fk-or]");
    private static final DateTimeFormatter HOUR_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH", Locale.US);

    private final RootMcPlugin plugin;
    private volatile boolean enabled = true;
    private volatile boolean discordRelayEnabled = true;
    private volatile boolean hourlyAnnouncer = true;
    /** Catalog body after Announcer {@code ::tax::} prefix — no legacy color spam. */
    private volatile String hourlyTemplate =
            "{dynamic_tax_pct}% · {grant_count} grants · {vote_count} votes · Discord {discord_count}";

    private final ConcurrentHashMap<String, CategoryBucket> buckets = new ConcurrentHashMap<>();
    private final AtomicReference<String> activeHourKey = new AtomicReference<>("");
    private volatile String cachedHourlyLine = "";

    public PublicReachoutService(RootMcPlugin plugin) {
        this.plugin = plugin;
        reloadFromConfig();
    }

    public void reloadFromConfig() {
        FileConfiguration cfg = plugin.rootMcYaml();
        var section = cfg.getConfigurationSection("public-reachout");
        enabled = section == null || section.getBoolean("enabled", true);
        discordRelayEnabled = section == null || section.getBoolean("relay-to-discord", true);
        hourlyAnnouncer = section == null || section.getBoolean("hourly-announcer", true);
        String template = section == null ? null : section.getString("hourly-summary");
        if (template != null && !template.isBlank()) {
            hourlyTemplate = template;
        }
        refreshHourlyLine();
    }

    @Override
    public void recordTreasuryOutflow(
            String category,
            String playerName,
            UUID playerUuid,
            double gold,
            boolean notifyImmediately) {
        if (!enabled || gold <= 0 || category == null || category.isBlank()) {
            return;
        }
        bucketForNow().record(category, gold);
        refreshHourlyLine();
        if (notifyImmediately && shouldNotifyImmediately(category)) {
            notify(category, playerName, playerUuid, gold);
        }
    }

    @Override
    public void relayGlobalBroadcast(String coloredOrPlainMessage, String kind) {
        if (!enabled || !discordRelayEnabled || coloredOrPlainMessage == null || coloredOrPlainMessage.isBlank()) {
            return;
        }
        relayToDiscordChat("Server", null, stripColors(coloredOrPlainMessage), kind == null ? "reachout" : kind);
    }

    @Override
    public String hourlyAnnouncerLine() {
        if (!enabled || !hourlyAnnouncer) {
            return "";
        }
        refreshHourlyLine();
        return cachedHourlyLine == null ? "" : cachedHourlyLine;
    }

    private boolean shouldNotifyImmediately(String category) {
        FileConfiguration cfg = plugin.rootMcYaml();
        String path = "public-reachout.immediate." + category.toLowerCase(Locale.ROOT);
        if (cfg.contains(path)) {
            return cfg.getBoolean(path);
        }
        return switch (category) {
            case "discord_activity", "discord_first_message", "discord_link" -> true;
            case "vote_milestone" -> true;
            case "command_test" -> true;
            default -> false;
        };
    }

    private void notify(String category, String playerName, UUID playerUuid, double gold) {
        String templateKey = "reachout-" + category.toLowerCase(Locale.ROOT).replace('_', '-');
        String template = plugin.rawMsg(templateKey);
        if (template == null || template.isBlank() || template.equals(templateKey)) {
            template = plugin.rawMsg("reachout-default");
        }
        if (template == null || template.isBlank()) {
            template = "&7{player} received &f{gold} G&7 from the server treasury.";
        }
        String name = playerName == null || playerName.isBlank() ? "Player" : playerName;
        String line = template
                .replace("{player}", name)
                .replace("{gold}", formatGold(gold));
        String colored = plugin.colorize(line);
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcastMessage(colored));
        relayToDiscordChat(name, playerUuid, stripColors(line), "reachout");
    }

    private void relayToDiscordChat(String username, UUID uuid, String plain, String kind) {
        if (!discordRelayEnabled) {
            return;
        }
        DiscordChatBridge bridge = plugin.discordChatBridge();
        if (bridge == null) {
            return;
        }
        bridge.relayReachout(
                username,
                uuid == null ? "" : uuid.toString(),
                plain,
                kind);
    }

    private void refreshHourlyLine() {
        String hourKey = currentHourKey();
        activeHourKey.set(hourKey);
        CategoryBucket bucket = buckets.get(hourKey);
        double grantGold = 0;
        int grantCount = 0;
        double voteGold = 0;
        int voteCount = 0;
        double discordGold = 0;
        int discordCount = 0;
        if (bucket != null && !bucket.isEmpty()) {
            grantGold = bucket.gold("grant");
            grantCount = bucket.count("grant");
            voteGold = bucket.gold("vote");
            voteCount = bucket.count("vote");
            discordGold = bucket.gold("discord_activity")
                    + bucket.gold("discord_first_message")
                    + bucket.gold("discord_link");
            discordCount = bucket.count("discord_activity")
                    + bucket.count("discord_first_message")
                    + bucket.count("discord_link");
        }

        String taxPct = formatTaxPercent();
        boolean hasTax = !taxPct.isBlank();
        boolean hasGrants = grantCount > 0 || voteCount > 0 || discordCount > 0;
        if (!hasTax && !hasGrants) {
            cachedHourlyLine = "";
            return;
        }
        if (!hasGrants) {
            cachedHourlyLine = "::tax::" + taxPct + "% · /pay · shops · /mint";
            return;
        }

        String line = hourlyTemplate
                .replace("{dynamic_tax_pct}", taxPct)
                .replace("{grant_count}", String.valueOf(grantCount))
                .replace("{grant_gold}", formatGold(grantGold))
                .replace("{vote_count}", String.valueOf(voteCount))
                .replace("{vote_gold}", formatGold(voteGold))
                .replace("{discord_count}", String.valueOf(discordCount))
                .replace("{discord_gold}", formatGold(discordGold));
        cachedHourlyLine = "::tax::" + line;
    }

    private String formatTaxPercent() {
        RootMcTreasuryService treasury = ShadedServiceBridge.resolveTreasury(plugin);
        if (treasury == null || !treasury.transactionTaxEnabled()) {
            return "";
        }
        double rate = treasury.effectiveTransactionTaxRate();
        if (rate <= 0) {
            return "";
        }
        double pct = rate * 100.0;
        if (pct == Math.rint(pct)) {
            return String.valueOf((long) pct);
        }
        return String.format(Locale.US, "%.3f", pct);
    }

    private CategoryBucket bucketForNow() {
        return buckets.computeIfAbsent(currentHourKey(), k -> new CategoryBucket());
    }

    private static String currentHourKey() {
        return ZonedDateTime.now(HST).format(HOUR_KEY);
    }

    private static String formatGold(double gold) {
        if (gold == Math.rint(gold)) {
            return String.valueOf((long) gold);
        }
        return String.format(Locale.US, "%.3f", gold);
    }

    private static String stripColors(String value) {
        if (value == null) {
            return "";
        }
        return COLOR_CODES.matcher(value).replaceAll("").trim();
    }

    /** Notify link bonus privately (treasury reachout still broadcasts when configured). */
    public void notifyDiscordLinkPrivate(org.bukkit.entity.Player player, double gold) {
        if (player == null) {
            return;
        }
        String template = plugin.msg("discord-link-bonus").replace("{gold}", formatGold(gold));
        player.sendMessage(template);
    }

    private static final class CategoryBucket {
        private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicReference<Double>> gold = new ConcurrentHashMap<>();

        void record(String category, double amount) {
            counts.computeIfAbsent(category, k -> new AtomicInteger()).incrementAndGet();
            gold.computeIfAbsent(category, k -> new AtomicReference<>(0.0))
                    .updateAndGet(v -> v + amount);
        }

        int count(String category) {
            AtomicInteger c = counts.get(category);
            return c == null ? 0 : c.get();
        }

        double gold(String category) {
            AtomicReference<Double> g = gold.get(category);
            return g == null ? 0.0 : g.get();
        }

        boolean isEmpty() {
            return counts.isEmpty();
        }
    }
}
