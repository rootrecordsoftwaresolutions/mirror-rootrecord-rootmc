package com.rootrecord.minecraft.rootmc.sync;

import com.rootrecord.minecraft.common.command.ServerRestartBridge;
import com.rootrecord.minecraft.rootmc.RootMcPlugin;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hands heartbeat jar downloads to Root-Restart (same countdown → restart-helper as midnight).
 */
final class HeartbeatUpdateRestart {

    private static final AtomicBoolean PENDING = new AtomicBoolean(false);

    private HeartbeatUpdateRestart() {}

    static void scheduleAfterUpdates(RootMcPlugin plugin, List<String> notes) {
        if (!PENDING.compareAndSet(false, true)) {
            plugin.getLogger().info("Heartbeat update restart already scheduled — skipping duplicate");
            return;
        }
        String summary = notes == null || notes.isEmpty() ? "plugin update(s)" : String.join(", ", notes);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (ServerRestartBridge.requestPluginUpdateRestart(summary)) {
                plugin.getLogger().info(
                        "Heartbeat updates: handed off to Root-Restart (same path as midnight)");
                PENDING.set(false);
                return;
            }
            plugin.getLogger().warning(
                    "Root-Restart unavailable — cannot auto-restart after heartbeat download. "
                            + "Install Root-Ops / ensure /rootrestart works. Jars are on disk; "
                            + "restart Paper manually or wait for midnight.");
            PENDING.set(false);
        });
    }
}
