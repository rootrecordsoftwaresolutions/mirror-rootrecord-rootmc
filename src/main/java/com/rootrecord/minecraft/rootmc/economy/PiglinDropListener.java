package com.rootrecord.minecraft.rootmc.economy;

import com.rootrecord.minecraft.rootmc.RootMcPlugin;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

/** Blocks piglin / zombified piglin death loot to discourage gold farms. */
public final class PiglinDropListener implements Listener {

    private final boolean enabled;

    public PiglinDropListener(RootMcPlugin plugin) {
        var cfg = plugin.getConfig().getConfigurationSection("economy.piglin-drops");
        this.enabled = cfg == null || cfg.getBoolean("enabled", true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!enabled) {
            return;
        }
        EntityType type = event.getEntityType();
        if (type != EntityType.PIGLIN
                && type != EntityType.PIGLIN_BRUTE
                && type != EntityType.ZOMBIFIED_PIGLIN) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
    }
}
