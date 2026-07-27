package com.rootrecord.minecraft.rootstat.economy;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** Reads cumulative gold-found stats from Root Essentials MySQL export. */
public final class GoldFoundCollector {

    private final Logger logger;

    public GoldFoundCollector(Logger logger) {
        this.logger = logger;
    }

    public List<EconomySnapshot.GoldFoundRow> collect() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Root-Essentials");
        if (plugin == null || !plugin.isEnabled()) {
            return List.of();
        }
        try {
            Method method = plugin.getClass().getMethod("allGoldFoundForSync");
            Object raw = method.invoke(plugin);
            if (!(raw instanceof List<?> list) || list.isEmpty()) {
                return List.of();
            }
            List<EconomySnapshot.GoldFoundRow> out = new ArrayList<>(list.size());
            for (Object row : list) {
                if (row == null) {
                    continue;
                }
                String uuid = String.valueOf(invoke(row, "uuid"));
                String username = String.valueOf(invoke(row, "username"));
                double total = ((Number) invoke(row, "totalGoldG")).doubleValue();
                if (!Double.isFinite(total) || total <= 0) {
                    continue;
                }
                out.add(new EconomySnapshot.GoldFoundRow(
                        uuid,
                        username,
                        total,
                        num(row, "minedOreG"),
                        num(row, "minedBlockG"),
                        num(row, "lootChestG"),
                        num(row, "lootMobG"),
                        num(row, "pickupG"),
                        ((Number) invoke(row, "findEvents")).intValue()));
            }
            logger.fine("Gold found export: " + out.size() + " players");
            return out;
        } catch (ReflectiveOperationException ex) {
            logger.fine("Root Essentials gold-found export unavailable: " + ex.getMessage());
            return List.of();
        }
    }

    private static double num(Object row, String accessor) throws ReflectiveOperationException {
        return ((Number) invoke(row, accessor)).doubleValue();
    }

    private static Object invoke(Object target, String accessor) throws ReflectiveOperationException {
        return target.getClass().getMethod(accessor).invoke(target);
    }
}
