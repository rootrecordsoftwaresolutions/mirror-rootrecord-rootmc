package com.rootrecord.minecraft.rootstat.economy.shop;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ReflectionShopSupport {

    private ReflectionShopSupport() {}

    public static Plugin pluginByNames(String... names) {
        for (String name : names) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
            if (plugin != null && plugin.isEnabled()) {
                return plugin;
            }
        }
        return null;
    }

    public static Object invokeNoArg(Object target, String... methodNames) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        for (String methodName : methodNames) {
            try {
                Method method = declaredOrPublic(type, methodName);
                if (method == null) {
                    continue;
                }
                method.setAccessible(true);
                return method.invoke(target);
            } catch (Throwable ignored) {
                // Optional shop plugins may pull types we cannot load — try next method/name.
            }
        }
        return null;
    }

    static Object invoke(Object target, String methodName, Class<?>[] paramTypes, Object... args) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName, paramTypes);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method declaredOrPublic(Class<?> type, String methodName) {
        try {
            return type.getDeclaredMethod(methodName);
        } catch (NoSuchMethodException ignored) {
            try {
                return type.getMethod(methodName);
            } catch (NoSuchMethodException ex) {
                return null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static Collection<Object> asCollection(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return (Collection<Object>) collection;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> out = new ArrayList<>();
            for (Object item : iterable) {
                out.add(item);
            }
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            return new ArrayList<>(map.values());
        }
        return List.of();
    }

    static Location locationFromShop(Object shop) {
        Object loc = invokeNoArg(shop, "getLocation", "location");
        if (loc instanceof Location location) {
            return location;
        }
        return null;
    }

    static double priceFromShop(Object shop) {
        Object value = invokeNoArg(shop, "getPrice", "price");
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return -1;
    }

    static boolean isBuyShop(Object shop) {
        Object value = invokeNoArg(shop, "isBuying", "isBuyShop");
        if (value instanceof Boolean bool) {
            return bool;
        }
        return false;
    }

    static String ownerUuid(Object shop) {
        Object value = invokeNoArg(shop, "getOwnerUniqueId", "getOwnerUuid", "getOwner");
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    static String ownerName(Object shop) {
        Object value = invokeNoArg(shop, "getOwnerName", "getOwnerUsername");
        if (value instanceof String name && !name.isBlank()) {
            return name;
        }
        return null;
    }

    static Object itemFromShop(Object shop) {
        return invokeNoArg(shop, "getItem", "item");
    }

    static String shopId(String providerId, Location location) {
        if (location == null || location.getWorld() == null) {
            return providerId + ":unknown";
        }
        return providerId + ":" + location.getWorld().getName()
                + ":" + location.getBlockX()
                + ":" + location.getBlockY()
                + ":" + location.getBlockZ();
    }

    static void logProbeFailure(Logger logger, String provider, Throwable ex) {
        logger.log(Level.FINE, "Shop provider " + provider + " probe failed: " + ex.getMessage());
    }

    static String normalizeListingType(String raw) {
        if (raw == null) {
            return "sell";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains("buy") ? "buy" : "sell";
    }
}
