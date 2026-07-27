package com.rootrecord.minecraft.rootstat.sync;

import com.rootrecord.minecraft.common.McDayClock;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** HST MC-day clock: beds reset phantom insomnia but cannot skip the night. */
public final class McDayBedListener implements Listener {

    private final JavaPlugin plugin;

    public McDayBedListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (!McDayClock.enabled()) {
            return;
        }
        if (event.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) {
            return;
        }
        Player player = event.getPlayer();
        player.setStatistic(Statistic.TIME_SINCE_REST, 0);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (player.isSleeping()) {
                player.wakeup(true);
                player.sendMessage(
                        "§7You rested — §fphantom insomnia reset§7. §8(Nights can't be skipped on the HST clock.)");
            }
        }, 1L);
        BukkitTask[] guard = new BukkitTask[1];
        guard[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                guard[0].cancel();
                return;
            }
            if (player.isSleeping()) {
                player.wakeup(true);
            }
        }, 2L, 2L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (guard[0] != null) {
                guard[0].cancel();
            }
        }, 40L);
    }
}
