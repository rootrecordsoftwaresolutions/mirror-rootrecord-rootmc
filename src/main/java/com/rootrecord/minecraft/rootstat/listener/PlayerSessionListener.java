package com.rootrecord.minecraft.rootstat.listener;

import com.rootrecord.minecraft.rootmc.discord.DiscordActivityRewardSessions;
import com.rootrecord.minecraft.rootmc.discord.DiscordLinkWelcomeSessions;
import com.rootrecord.minecraft.rootstat.RootStatBridge;
import com.rootrecord.minecraft.rootstat.model.LinkedPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks playtime sessions, cloud sync on join, and linked-player cache refresh. */
public final class PlayerSessionListener implements Listener {

    private final RootStatBridge bridge;
    private final Map<UUID, Long> sessionStartMs = new ConcurrentHashMap<>();
    private final BukkitTask periodicFlushTask;

    public PlayerSessionListener(RootStatBridge bridge) {
        this.bridge = bridge;
        // Persist active sessions every minute so playtime-driven commands update without relog.
        this.periodicFlushTask = bridge.getPlugin().getServer().getScheduler().runTaskTimerAsynchronously(
                bridge.getPlugin(),
                this::flushActiveSessions,
                20L * 60L,
                20L * 60L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        sessionStartMs.put(uuid, System.currentTimeMillis());

        if (Bukkit.getPluginManager().isPluginEnabled("Root-Times")) {
            // Root-Times owns playtime harvest to avoid double-counting.
            bridge.getPlugin().getServer().getScheduler().runTaskAsynchronously(bridge.getPlugin(), () -> {
                try {
                    var status = bridge.cloud().linkStatus(uuid.toString());
                    if (status.linked() && bridge.players() != null) {
                        bridge.players().upsert(new LinkedPlayer(
                                uuid.toString(),
                                player.getName(),
                                status.accountId(),
                                status.email(),
                                status.verifiedAt(),
                                java.time.Instant.now().toString()));
                    }
                    bridge.getPlugin().getServer().getScheduler().runTask(bridge.getPlugin(), () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (status.discordLinked() && isRecentDiscordLink(status.verifiedAt())) {
                            if (DiscordLinkWelcomeSessions.markWelcome(uuid)) {
                                player.sendMessage(bridge.colorize(bridge.msg("discord-linked-welcome")));
                            }
                        }
                    });
                } catch (Exception ignored) {
                    // join should not fail on cloud errors
                }
                if (bridge.governancePower() != null) {
                    bridge.governancePower().refreshAsync(uuid);
                }
            });
            return;
        }

        bridge.getPlugin().getServer().getScheduler().runTaskAsynchronously(bridge.getPlugin(), () -> {
            try {
                if (bridge.playtime() != null) {
                    bridge.playtime().recordLogin(uuid, player.getName());
                }
            } catch (Exception ignored) {
                // non-fatal
            }

            try {
                var status = bridge.cloud().linkStatus(uuid.toString());
                if (status.linked() && bridge.players() != null) {
                    bridge.players().upsert(new LinkedPlayer(
                            uuid.toString(),
                            player.getName(),
                            status.accountId(),
                            status.email(),
                            status.verifiedAt(),
                            java.time.Instant.now().toString()));
                }
                bridge.getPlugin().getServer().getScheduler().runTask(bridge.getPlugin(), () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (status.discordLinked() && isRecentDiscordLink(status.verifiedAt())) {
                        if (DiscordLinkWelcomeSessions.markWelcome(uuid)) {
                            player.sendMessage(bridge.colorize(bridge.msg("discord-linked-welcome")));
                        }
                    }
                });
            } catch (Exception ignored) {
                // join should not fail on cloud errors
            }

            if (bridge.governancePower() != null) {
                bridge.governancePower().refreshAsync(uuid);
            }
        });
    }

    private static boolean isRecentDiscordLink(String verifiedAtIso) {
        if (verifiedAtIso == null || verifiedAtIso.isBlank()) {
            return false;
        }
        try {
            long verifiedMs = java.time.Instant.parse(verifiedAtIso).toEpochMilli();
            return System.currentTimeMillis() - verifiedMs <= 20L * 60L * 1000L;
        } catch (Exception ex) {
            return false;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        DiscordActivityRewardSessions.clearSession(uuid);
        DiscordLinkWelcomeSessions.clearSession(uuid);
        if (Bukkit.getPluginManager().isPluginEnabled("Root-Times")) {
            sessionStartMs.remove(uuid);
            return;
        }
        Long started = sessionStartMs.remove(uuid);
        if (started == null || bridge.playtime() == null) {
            return;
        }
        long seconds = Math.max(0L, (System.currentTimeMillis() - started) / 1000L);
        if (seconds <= 0) {
            return;
        }
        bridge.getPlugin().getServer().getScheduler().runTaskAsynchronously(bridge.getPlugin(), () -> {
            try {
                bridge.playtime().addSession(uuid, seconds);
            } catch (Exception ignored) {
                // non-fatal
            }
        });
    }

    private void flushActiveSessions() {
        if (Bukkit.getPluginManager().isPluginEnabled("Root-Times")) {
            return;
        }
        if (bridge.playtime() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (var player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            Long started = sessionStartMs.get(uuid);
            if (started == null) {
                sessionStartMs.put(uuid, now);
                continue;
            }
            long seconds = Math.max(0L, (now - started) / 1000L);
            if (seconds <= 0L) {
                continue;
            }
            sessionStartMs.put(uuid, now);
            try {
                bridge.playtime().addSession(uuid, seconds);
            } catch (Exception ignored) {
                // non-fatal
            }
        }
        // Playtime is included in the periodic cloud sync — no full economy rescan every minute.
    }
}
