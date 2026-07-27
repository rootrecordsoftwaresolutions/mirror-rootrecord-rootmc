package com.rootrecord.minecraft.rootstat;

import com.rootrecord.minecraft.common.RootMcEconomyResolver;
import com.rootrecord.minecraft.common.RootMcEconomyService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RootStatExpansion extends PlaceholderExpansion {

    private static final Pattern NAMED =
            Pattern.compile("^(mcmmo_power|mcmmo_power_level|playtime|mob_kills|deaths)_(.+)$");

    private final RootStatBridge bridge;
    private final String identifier;

    public RootStatExpansion(RootStatBridge bridge) {
        this(bridge, "rootmc");
    }

    public RootStatExpansion(RootStatBridge bridge, String identifier) {
        this.bridge = bridge;
        this.identifier = identifier;
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public String getAuthor() {
        return "Root Record";
    }

    @Override
    public String getVersion() {
        return bridge.getPlugin().getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String key = params == null ? "" : params.toLowerCase(Locale.ROOT);
        try {
            Matcher named = NAMED.matcher(key);
            if (named.matches()) {
                return resolveNamed(named.group(1), named.group(2));
            }
            if (player == null) {
                return "";
            }
            UUID uuid = player.getUniqueId();
            String name = player.getName() == null ? "" : player.getName();
            return switch (key) {
                case "balance", "balance_formatted" -> formatBalance(uuid);
                case "balance_int" -> balanceInt(uuid);
                case "verified" -> verified(uuid);
                case "account_id", "account" -> accountId(uuid);
                case "email" -> email(uuid);
                case "mcmmo_power", "mcmmo_power_level" -> mcmmoPower(name);
                case "playtime" -> playtime(name);
                case "mob_kills" -> mobKills(name);
                case "deaths" -> deaths(name);
                case "governance_power", "vote_power", "voting_power" -> governanceShare(player.getUniqueId());
                case "governance_prefix", "vote_prefix", "voting_power_prefix" -> governancePrefix(player.getUniqueId());
                default -> "";
            };
        } catch (Exception ex) {
            return "";
        }
    }

    private String resolveNamed(String metric, String username) {
        return switch (metric) {
            case "mcmmo_power", "mcmmo_power_level" -> mcmmoPower(username);
            case "playtime" -> playtime(username);
            case "mob_kills" -> mobKills(username);
            case "deaths" -> deaths(username);
            default -> "";
        };
    }

    private String verified(UUID uuid) {
        if (bridge.players() == null) {
            return "";
        }
        try {
            return bridge.players().findByUuid(uuid).map(row -> row.verified() ? "true" : "false").orElse("false");
        } catch (Exception ex) {
            return "";
        }
    }

    private String accountId(UUID uuid) {
        if (bridge.players() == null) {
            return "";
        }
        try {
            return bridge.players().findByUuid(uuid).map(row -> row.accountId() == null ? "" : row.accountId()).orElse("");
        } catch (Exception ex) {
            return "";
        }
    }

    private String email(UUID uuid) {
        if (bridge.players() == null) {
            return "";
        }
        try {
            return bridge.players().findByUuid(uuid).map(row -> row.email() == null ? "" : row.email()).orElse("");
        } catch (Exception ex) {
            return "";
        }
    }

    private String mcmmoPower(String username) {
        if (bridge.mcmmo() == null || username == null || username.isBlank()) {
            return "";
        }
        try {
            return bridge.mcmmo().powerByUsername(username).map(String::valueOf).orElse("0");
        } catch (Exception ex) {
            return "";
        }
    }

    private String playtime(String username) {
        if (username == null || username.isBlank()) {
            return "";
        }
        try {
            if (bridge.playtime() != null) {
                var row = bridge.playtime().findByUsername(username);
                if (row.isPresent()) {
                    return formatPlaytime(row.get().totalPlaytimeSeconds());
                }
            }
            OfflinePlayer offline = Bukkit.getOfflinePlayer(username);
            if (offline.hasPlayedBefore()) {
                return formatPlaytime(offline.getStatistic(Statistic.PLAY_ONE_MINUTE) * 60L);
            }
        } catch (Exception ignored) {
        }
        return "0m";
    }

    private String mobKills(String username) {
        return statistic(username, Statistic.MOB_KILLS);
    }

    private String deaths(String username) {
        return statistic(username, Statistic.DEATHS);
    }

    private static String statistic(String username, Statistic stat) {
        if (username == null || username.isBlank()) {
            return "";
        }
        try {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(username);
            if (!offline.hasPlayedBefore()) {
                return "0";
            }
            return String.valueOf(offline.getStatistic(stat));
        } catch (Exception ex) {
            return "0";
        }
    }

    private static String formatPlaytime(long totalSeconds) {
        if (totalSeconds <= 0) {
            return "0m";
        }
        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    private String balanceInt(UUID uuid) {
        RootMcEconomyService economy = economy();
        if (economy == null) {
            return "";
        }
        return String.valueOf((long) Math.floor(economy.balance(uuid)));
    }

    private String formatBalance(UUID uuid) {
        RootMcEconomyService economy = economy();
        if (economy == null) {
            return "";
        }
        double balance = economy.balance(uuid);
        if (!Double.isFinite(balance)) {
            return "";
        }
        return String.format(Locale.US, "%.3f", balance);
    }

    private RootMcEconomyService economy() {
        if (!(bridge.getPlugin() instanceof JavaPlugin plugin)) {
            return null;
        }
        RootMcEconomyService resolved = RootMcEconomyResolver.resolve(plugin);
        if (resolved != null) {
            return resolved;
        }
        Plugin essentials = Bukkit.getPluginManager().getPlugin("Root-Essentials");
        if (essentials instanceof RootMcEconomyService economy) {
            return economy;
        }
        return null;
    }

    private String governanceShare(UUID uuid) {
        var cache = bridge.governancePower();
        return cache == null ? "" : cache.sharePercent(uuid);
    }

    private String governancePrefix(UUID uuid) {
        var cache = bridge.governancePower();
        if (cache == null) {
            return "";
        }
        String raw = cache.chatPrefix(uuid);
        return raw.isEmpty() ? "" : bridge.colorize(raw);
    }
}
