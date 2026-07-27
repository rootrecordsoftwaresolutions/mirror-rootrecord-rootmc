package com.rootrecord.minecraft.rootmc.sync;

import com.rootrecord.minecraft.rootmc.RootMcPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Level;

public final class HeartbeatTask {

    private final RootMcPlugin plugin;
    private BukkitTask repeatingTask;

    public HeartbeatTask(RootMcPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        long intervalTicks = plugin.rootMcConfig().heartbeatIntervalMinutes() * 60L * 20L;
        repeatingTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, this::runSafe, 60L, intervalTicks);
    }

    public void stop() {
        if (repeatingTask != null) {
            repeatingTask.cancel();
            repeatingTask = null;
        }
    }

    public void runSafe() {
        int onlinePlayers = plugin.getServer().getOnlinePlayers().size();
        if (plugin.reporting() != null) {
            try {
                plugin.reporting().upsertServerStatus(
                        plugin.rootMcConfig().serverId(),
                        onlinePlayers,
                        plugin.getDescription().getVersion(),
                        plugin.getServer().getMinecraftVersion());
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "RootMC MySQL status update failed: " + ex.getMessage(), ex);
            }
        }
        if (!plugin.rootMcConfig().hasServerCredentials()) {
            return;
        }
        // Always heartbeat (including empty server) so jar pulls + maintenance restarts can run.
        try {
            String body = plugin.heartbeatClient().sendHeartbeat(onlinePlayers);
            plugin.updates().apply(HeartbeatResultParser.parse(body));
            plugin.syncTask().runPendingPayoutsSafe();
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "RootMC heartbeat failed: " + ex.getMessage(), ex);
        }
    }
}
