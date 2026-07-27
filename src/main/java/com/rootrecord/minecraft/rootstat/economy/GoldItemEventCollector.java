package com.rootrecord.minecraft.rootstat.economy;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** Incremental gold-item provenance export from Root Essentials MySQL. */
public final class GoldItemEventCollector {

    private final Logger logger;
    private volatile long lastEventId;

    public GoldItemEventCollector(Logger logger) {
        this.logger = logger;
    }

    public void setLastEventId(long id) {
        this.lastEventId = Math.max(0, id);
    }

    public long lastEventId() {
        return lastEventId;
    }

    public List<EconomySnapshot.GoldItemEventRow> collect() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Root-Essentials");
        if (plugin == null || !plugin.isEnabled()) {
            return List.of();
        }
        try {
            Method method = plugin.getClass().getMethod("goldItemEventsAfter", long.class, int.class);
            Object raw = method.invoke(plugin, lastEventId, 400);
            if (!(raw instanceof List<?> list) || list.isEmpty()) {
                return List.of();
            }
            List<EconomySnapshot.GoldItemEventRow> out = new ArrayList<>(list.size());
            for (Object row : list) {
                if (row == null) {
                    continue;
                }
                long eventId = ((Number) invoke(row, "eventId")).longValue();
                out.add(new EconomySnapshot.GoldItemEventRow(
                        eventId,
                        String.valueOf(invoke(row, "uuid")),
                        String.valueOf(invoke(row, "username")),
                        String.valueOf(invoke(row, "eventType")),
                        String.valueOf(invoke(row, "obtainedVia")),
                        String.valueOf(invoke(row, "material")),
                        ((Number) invoke(row, "stackAmount")).intValue(),
                        ((Number) invoke(row, "goldG")).doubleValue(),
                        nullableString(invoke(row, "world")),
                        nullableInt(invoke(row, "blockX")),
                        nullableInt(invoke(row, "blockY")),
                        nullableInt(invoke(row, "blockZ")),
                        nullableString(invoke(row, "contextJson")),
                        String.valueOf(invoke(row, "createdAt"))));
                if (eventId > lastEventId) {
                    lastEventId = eventId;
                }
            }
            logger.fine("Gold item events export: " + out.size());
            return out;
        } catch (ReflectiveOperationException ex) {
            logger.fine("Root Essentials gold-item export unavailable: " + ex.getMessage());
            return List.of();
        }
    }

    private static String nullableString(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return "null".equals(s) ? null : s;
    }

    private static Integer nullableInt(Object value) {
        if (value == null) {
            return null;
        }
        return ((Number) value).intValue();
    }

    private static Object invoke(Object target, String accessor) throws ReflectiveOperationException {
        return target.getClass().getMethod(accessor).invoke(target);
    }
}
