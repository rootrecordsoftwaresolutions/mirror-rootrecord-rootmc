package com.rootrecord.minecraft.rootstat.sync;

import com.rootrecord.minecraft.common.McDayClock;
import com.rootrecord.minecraft.rootstat.RootStatBridge;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

/** Drives world time from the HST-aligned {@link McDayClock}. */
public final class McDayWorldSync {

    private final RootStatBridge bridge;
    private BukkitTask task;
    private McDayGrowthRules growthRules;
    private long virtualFullTime = -1L;
    private long lastActiveMillis = -1L;

    public McDayWorldSync(RootStatBridge bridge) {
        this.bridge = bridge;
    }

    public void start() {
        stop();
        if (!McDayClock.enabled()) {
            return;
        }
        growthRules = new McDayGrowthRules(bridge.getPlugin());
        growthRules.start();
        World world = resolveDayWorld();
        if (world != null) {
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            world.setGameRule(GameRule.PLAYERS_SLEEPING_PERCENTAGE, 101);
            virtualFullTime = world.getFullTime();
            apply(world, !bridge.getPlugin().getServer().getOnlinePlayers().isEmpty());
        }
        task = bridge.getPlugin().getServer().getScheduler().runTaskTimer(
                bridge.getPlugin(), this::tick, 20L, 20L);
        bridge.getPlugin().getLogger().info(
                "Minecraft day length "
                        + McDayClock.lengthMinutes()
                        + " active min (configured zone "
                        + McDayClock.zone().getId()
                        + "); pauses while empty and resumes last world time; random tick speed "
                        + McDayClock.randomTickSpeed()
                        + " (Spigot growth modifiers ~"
                        + McDayClock.spigotGrowthModifierPercent()
                        + "% in spigot.yml); bone meal, bees, and farmer villagers scaled in-plugin.");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        lastActiveMillis = -1L;
        if (growthRules != null) {
            growthRules.stop();
            growthRules = null;
        }
    }

    private void tick() {
        World world = resolveDayWorld();
        if (world != null) {
            boolean active = !bridge.getPlugin().getServer().getOnlinePlayers().isEmpty();
            apply(world, active);
        }
    }

    private void apply(World world, boolean active) {
        if (virtualFullTime < 0) {
            virtualFullTime = world.getFullTime();
        }
        if (active) {
            long now = System.currentTimeMillis();
            if (lastActiveMillis > 0) {
                long elapsedMillis = Math.max(0L, now - lastActiveMillis);
                long elapsedTicks = Math.max(1L, Math.round(
                        elapsedMillis * (McDayClock.TICKS_PER_DAY / (McDayClock.lengthMinutes() * 60_000.0))));
                virtualFullTime += elapsedTicks;
            }
            lastActiveMillis = now;
        } else {
            virtualFullTime = world.getFullTime();
            lastActiveMillis = -1L;
        }
        world.setFullTime(virtualFullTime);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.PLAYERS_SLEEPING_PERCENTAGE, 101);
        if (growthRules != null) {
            growthRules.applyAll(active);
        }
    }

    private World resolveDayWorld() {
        String named = bridge.config().dayWorld();
        if (named != null && !named.isBlank()) {
            World world = Bukkit.getWorld(named);
            if (world != null) {
                return world;
            }
        }
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.NORMAL) {
                return world;
            }
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
    }
}
