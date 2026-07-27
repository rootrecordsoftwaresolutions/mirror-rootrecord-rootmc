package com.rootrecord.minecraft.rootstat.economy;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Locale;

/** Towny claim attribution for physical gold scans (reflection — no compile-time Towny dep). */
public final class TownyGoldHelper {

    private TownyGoldHelper() {}

    public static boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin("Towny") != null;
    }

    /** Resident UUID for a block inside a town plot, or null when wilderness / unavailable. */
    public static String residentUuid(Block block) {
        if (!isAvailable() || block == null) {
            return null;
        }
        try {
            Object api = townyApi();
            if (api == null) {
                return null;
            }
            Object townBlock = invoke(api, "getTownBlock", block.getLocation());
            if (townBlock == null) {
                return null;
            }
            Object resident = invokeNoArg(townBlock, "getResident");
            if (resident == null) {
                Object town = invokeNoArg(townBlock, "getTown");
                Object mayor = town == null ? null : invokeNoArg(town, "getMayor");
                resident = mayor;
            }
            if (resident == null) {
                return null;
            }
            Object uuid = invokeNoArg(resident, "getUUID", "getUuid");
            return uuid == null ? null : String.valueOf(uuid).toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String residentName(Block block) {
        if (!isAvailable() || block == null) {
            return null;
        }
        try {
            Object api = townyApi();
            if (api == null) {
                return null;
            }
            Object townBlock = invoke(api, "getTownBlock", block.getLocation());
            if (townBlock == null) {
                return null;
            }
            Object resident = invokeNoArg(townBlock, "getResident");
            if (resident == null) {
                Object town = invokeNoArg(townBlock, "getTown");
                Object mayor = town == null ? null : invokeNoArg(town, "getMayor");
                resident = mayor;
            }
            if (resident == null) {
                return null;
            }
            Object name = invokeNoArg(resident, "getName");
            return name == null ? null : String.valueOf(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Sample placed gold blocks in a loaded chunk (Towny claims only). */
    public static void scanChunkPlacedGold(
            Chunk chunk,
            java.util.Map<String, PhysicalGoldStorageScanner.MutableRow> byUuid,
            GoldBreakdown unattributedOut) {
        if (chunk == null || !isAvailable()) {
            return;
        }
        World world = chunk.getWorld();
        if (world == null) {
            return;
        }
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        int minY = Math.max(world.getMinHeight(), 48);
        int maxY = Math.min(world.getMaxHeight() - 1, 220);
        for (int x = 0; x < 16; x += 2) {
            for (int z = 0; z < 16; z += 2) {
                for (int y = minY; y <= maxY; y += 4) {
                    Block block = world.getBlockAt(baseX + x, y, baseZ + z);
                    if (block.getType() != Material.GOLD_BLOCK) {
                        continue;
                    }
                    String uuid = residentUuid(block);
                    if (uuid != null && !uuid.isBlank()) {
                        PhysicalGoldStorageScanner.MutableRow row =
                                byUuid.computeIfAbsent(uuid.toLowerCase(Locale.ROOT), k -> {
                                    String name = residentName(block);
                                    return new PhysicalGoldStorageScanner.MutableRow(uuid, name);
                                });
                        if (row.username == null || row.username.isBlank()) {
                            row.username = residentName(block);
                        }
                        row.towny.addPlacedBlock(Material.GOLD_BLOCK);
                    } else if (unattributedOut != null) {
                        unattributedOut.addPlacedBlock(Material.GOLD_BLOCK);
                    }
                }
            }
        }
    }

    private static Object townyApi() {
        try {
            Class<?> apiClass = Class.forName("com.palmergames.bukkit.towny.TownyAPI");
            Method method = apiClass.getMethod("getInstance");
            return method.invoke(null);
        } catch (Throwable ex) {
            return null;
        }
    }

    private static Object invoke(Object target, String methodName, Location arg) {
        if (target == null) {
            return null;
        }
        try {
            for (Method method : target.getClass().getMethods()) {
                if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
                    continue;
                }
                Class<?> param = method.getParameterTypes()[0];
                if (!param.isInstance(arg)) {
                    continue;
                }
                method.setAccessible(true);
                return method.invoke(target, arg);
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String... methodNames) {
        if (target == null) {
            return null;
        }
        for (String name : methodNames) {
            try {
                Method method = target.getClass().getMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (Throwable ignored) {
                // try next
            }
        }
        return null;
    }
}
