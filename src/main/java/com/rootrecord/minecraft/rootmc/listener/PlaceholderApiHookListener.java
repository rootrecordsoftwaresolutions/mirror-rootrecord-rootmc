package com.rootrecord.minecraft.rootmc.listener;

import com.rootrecord.minecraft.rootmc.RootMcPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;

/** Registers rootmc PAPI expansions when PlaceholderAPI enables after RootMC. */
public final class PlaceholderApiHookListener implements Listener {

    private final RootMcPlugin plugin;

    public PlaceholderApiHookListener(RootMcPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (!"PlaceholderAPI".equals(event.getPlugin().getName())) {
            return;
        }
        plugin.registerPlaceholderExpansionIfPresent();
    }
}
