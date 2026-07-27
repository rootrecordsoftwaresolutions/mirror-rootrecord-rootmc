package com.rootrecord.minecraft.rootstat.sync;

import com.rootrecord.minecraft.common.McDayClock;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;

/** Scales random-tick growth to the HST MC-day length on every world. */
public final class McDayGrowthRules implements Listener {

    private final Plugin plugin;
    private McDayExtraGrowthListener extraGrowth;

    public McDayGrowthRules(Plugin plugin) {
        this.plugin = plugin;
    }

    public void applyAll() {
        applyAll(true);
    }

    public void applyAll(boolean active) {
        if (!McDayClock.enabled()) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            apply(world, active);
        }
    }

    public void apply(World world) {
        apply(world, true);
    }

    public void apply(World world, boolean active) {
        if (!McDayClock.enabled() || world == null) {
            return;
        }
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, active ? McDayClock.randomTickSpeed() : 0);
    }

    public void start() {
        applyAll();
        HandlerList.unregisterAll(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        extraGrowth = new McDayExtraGrowthListener();
        Bukkit.getPluginManager().registerEvents(extraGrowth, plugin);
    }

    public void stop() {
        HandlerList.unregisterAll(this);
        if (extraGrowth != null) {
            HandlerList.unregisterAll(extraGrowth);
            extraGrowth = null;
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        apply(event.getWorld());
    }
}
