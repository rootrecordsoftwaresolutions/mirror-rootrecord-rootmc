package com.rootrecord.minecraft.rootstat.sync;

import com.rootrecord.minecraft.common.McDayClock;
import com.rootrecord.minecraft.rootstat.RootStatBridge;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.function.BiConsumer;

/** Single Minecraft day clock for the ordered economy rollover pipeline. */
public final class McDayRolloverTicker {

    private final RootStatBridge bridge;
    private final BiConsumer<Long, Long> rollover;
    private long lastMcDayId = -1L;
    private BukkitTask task;

    public McDayRolloverTicker(RootStatBridge bridge, BiConsumer<Long, Long> rollover) {
        this.bridge = bridge;
        this.rollover = rollover;
    }

    public void start() {
        stop();
        lastMcDayId = currentMcDayId();
        task = bridge.getPlugin().getServer().getScheduler().runTaskTimer(
                bridge.getPlugin(), this::tick, 40L, 100L);
        bridge.getPlugin().getLogger().info(
                McDayClock.enabled()
                        ? ("Minecraft day rollover coordinator active (HST "
                        + McDayClock.zone().getId()
                        + ", "
                        + McDayClock.lengthMinutes()
                        + " min/day"
                        + (bridge.config().dayWorld().isBlank() ? "" : ", world " + bridge.config().dayWorld())
                        + ", paused while empty).")
                        : ("Minecraft day rollover coordinator active (every "
                        + bridge.config().mcDayTicks()
                        + " ticks"
                        + (bridge.config().dayWorld().isBlank() ? "" : ", world " + bridge.config().dayWorld())
                        + ")."));
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        long current = currentMcDayId();
        if (lastMcDayId < 0) {
            lastMcDayId = current;
            return;
        }
        if (current <= lastMcDayId) {
            return;
        }
        long firstCompleted = lastMcDayId;
        lastMcDayId = current;
        rollover.accept(firstCompleted, current);
    }

    private long currentMcDayId() {
        World world = resolveDayWorld();
        if (world == null) {
            return 0L;
        }
        long ticksPerDay = McDayClock.enabled() ? McDayClock.TICKS_PER_DAY : bridge.config().mcDayTicks();
        return world.getFullTime() / Math.max(1L, ticksPerDay);
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
