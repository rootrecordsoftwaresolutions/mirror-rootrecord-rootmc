package com.rootrecord.minecraft.rootstat.governance;

import com.rootrecord.minecraft.rootstat.RootStatBridge;
import com.rootrecord.minecraft.rootstat.cloud.CloudApiClient;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Cached governance voting-power share for chat prefixes; refreshed each MC day and on join. */
public final class GovernancePowerCacheService {

    private final RootStatBridge bridge;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    public GovernancePowerCacheService(RootStatBridge bridge) {
        this.bridge = bridge;
    }

    public void refreshAsync(UUID uuid) {
        refreshAsync(uuid, false);
    }

    public void refreshAsync(UUID uuid, boolean notifyOnChange) {
        if (uuid == null) {
            return;
        }
        bridge.getPlugin().getServer().getScheduler().runTaskAsynchronously(bridge.getPlugin(), () -> {
            Entry previous = cache.get(uuid);
            Entry next = fetchEntry(uuid);
            if (next == null) {
                cache.remove(uuid);
                return;
            }
            cache.put(uuid, next);
            if (notifyOnChange && previous != null && previous.eligible() && next.eligible()) {
                String was = formatPercent(previous.sharePercent());
                String now = formatPercent(next.sharePercent());
                // Skip no-op display (e.g. 66.5% and 66.7% both round to 67)
                if (!was.equals(now) && Math.abs(previous.sharePercent() - next.sharePercent()) >= 0.05) {
                    notifyChange(uuid, was, now);
                }
            }
        });
    }

    public void refreshAllOnlineAsync(boolean notifyOnChange) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshAsync(player.getUniqueId(), notifyOnChange);
        }
    }

    public String chatPrefix(UUID uuid) {
        Entry entry = cache.get(uuid);
        if (entry == null || !entry.eligible() || entry.sharePercent() <= 0) {
            return "";
        }
        return "&7[" + formatPercent(entry.sharePercent()) + "%]&r ";
    }

    public String sharePercent(UUID uuid) {
        Entry entry = cache.get(uuid);
        if (entry == null || !entry.eligible() || entry.sharePercent() <= 0) {
            return "";
        }
        return formatPercent(entry.sharePercent());
    }

    private Entry fetchEntry(UUID uuid) {
        try {
            LocalGovernancePowerService local = new LocalGovernancePowerService(bridge);
            CloudApiClient.GovernanceVotingPower power = local.resolve(uuid);
            if (!power.ok()) {
                return new Entry(0, false, System.currentTimeMillis());
            }
            return new Entry(power.sharePercent(), power.eligible(), System.currentTimeMillis());
        } catch (Exception ex) {
            return null;
        }
    }

    private void notifyChange(UUID uuid, String previous, String current) {
        bridge.getPlugin().getServer().getScheduler().runTask(bridge.getPlugin(), () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                return;
            }
            com.rootrecord.minecraft.common.ChatUi.entry(
                    player, "Vote", previous + "% → " + current + "%");
        });
    }

    static String formatPercent(double pct) {
        if (!Double.isFinite(pct) || pct <= 0) {
            return "0";
        }
        if (pct >= 10) {
            return String.valueOf(Math.round(pct));
        }
        if (pct >= 1) {
            return String.format(Locale.US, "%.1f", pct);
        }
        return String.format(Locale.US, "%.2f", pct);
    }

    record Entry(double sharePercent, boolean eligible, long updatedAtMs) {}
}
