package com.rootrecord.minecraft.rootstat.towny;

import com.rootrecord.minecraft.rootstat.economy.shop.ReflectionShopSupport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads Towny towns/nations via reflection when the Towny plugin is present.
 */
public final class TownySnapshotCollector {

    private TownySnapshotCollector() {}

    public static boolean isAvailable() {
        return ReflectionShopSupport.pluginByNames("Towny") != null;
    }

    public static Map<String, Object> collectSnapshot(Logger logger) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("towns", List.of());
        out.put("nations", List.of());
        if (!isAvailable()) {
            return out;
        }
        try {
            Object api = townyApi();
            if (api == null) {
                return out;
            }
            out.put("towns", collectTowns(api));
            out.put("nations", collectNations(api));
        } catch (Throwable ex) {
            logger.log(Level.WARNING, "Towny snapshot failed: " + ex.getMessage(), ex);
        }
        return out;
    }

    private static Object townyApi() {
        if (ReflectionShopSupport.pluginByNames("Towny") == null) {
            return null;
        }
        try {
            Class<?> apiClass = Class.forName("com.palmergames.bukkit.towny.TownyAPI");
            var method = apiClass.getMethod("getInstance");
            return method.invoke(null);
        } catch (Throwable ex) {
            return null;
        }
    }

    private static List<Map<String, Object>> collectTowns(Object api) {
        Object towns = ReflectionShopSupport.invokeNoArg(api, "getTowns");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object town : ReflectionShopSupport.asCollection(towns)) {
            Map<String, Object> row = townRow(town);
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static List<Map<String, Object>> collectNations(Object api) {
        Object nations = ReflectionShopSupport.invokeNoArg(api, "getNations");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object nation : ReflectionShopSupport.asCollection(nations)) {
            Map<String, Object> row = nationRow(nation);
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static Map<String, Object> townRow(Object town) {
        if (town == null) {
            return null;
        }
        String name = stringOrNull(ReflectionShopSupport.invokeNoArg(town, "getName"));
        UUID uuid = uuidOrNull(ReflectionShopSupport.invokeNoArg(town, "getUUID", "getTownUUID"));
        if (name == null || uuid == null) {
            return null;
        }
        Object mayor = ReflectionShopSupport.invokeNoArg(town, "getMayor");
        Object nation = ReflectionShopSupport.invokeNoArg(town, "getNationOrNull", "getNation");
        int residents = intOrZero(ReflectionShopSupport.invokeNoArg(town, "getNumResidents"));
        if (residents <= 0) {
            Object residentList = ReflectionShopSupport.invokeNoArg(town, "getResidents");
            residents = ReflectionShopSupport.asCollection(residentList).size();
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("town_uuid", uuid.toString());
        row.put("town_name", name);
        row.put("resident_count", residents);
        if (mayor != null) {
            row.put("mayor_uuid", uuidString(ReflectionShopSupport.invokeNoArg(mayor, "getUUID")));
            row.put("mayor_name", stringOrNull(ReflectionShopSupport.invokeNoArg(mayor, "getName")));
        }
        if (nation != null) {
            row.put("nation_name", stringOrNull(ReflectionShopSupport.invokeNoArg(nation, "getName")));
            row.put("is_capital", isCapitalTown(town, nation));
        } else {
            row.put("is_capital", false);
        }
        row.put("plot_count", plotCount(town));
        row.put("town_balance_gold", townBankBalance(town));
        return row;
    }

    private static int plotCount(Object town) {
        Object townBlocks = ReflectionShopSupport.invokeNoArg(town, "getTownBlocks");
        int fromBlocks = ReflectionShopSupport.asCollection(townBlocks).size();
        if (fromBlocks > 0) {
            return fromBlocks;
        }
        int purchased = intOrZero(ReflectionShopSupport.invokeNoArg(town, "getPurchasedBlocks"));
        if (purchased > 0) {
            return purchased;
        }
        Object blocksMap = ReflectionShopSupport.invokeNoArg(town, "getTownBlocksMap");
        if (blocksMap instanceof Map<?, ?> map && !map.isEmpty()) {
            return map.size();
        }
        return intOrZero(ReflectionShopSupport.invokeNoArg(town, "getNumTownBlocks"));
    }

    private static double townBankBalance(Object town) {
        Object account = ReflectionShopSupport.invokeNoArg(town, "getAccount");
        if (account != null) {
            Object balance = ReflectionShopSupport.invokeNoArg(
                    account, "getHoldingBalance", "getBalance", "getCachedBalance");
            if (balance instanceof Number number) {
                double value = number.doubleValue();
                if (Double.isFinite(value)) {
                    return Math.max(0, value);
                }
            }
        }
        Object direct = ReflectionShopSupport.invokeNoArg(town, "getBalance", "getAccountBalance");
        if (direct instanceof Number number) {
            double value = number.doubleValue();
            if (Double.isFinite(value)) {
                return Math.max(0, value);
            }
        }
        return 0;
    }

    private static Map<String, Object> nationRow(Object nation) {
        if (nation == null) {
            return null;
        }
        String name = stringOrNull(ReflectionShopSupport.invokeNoArg(nation, "getName"));
        UUID uuid = uuidOrNull(ReflectionShopSupport.invokeNoArg(nation, "getUUID", "getNationUUID"));
        if (name == null || uuid == null) {
            return null;
        }
        Object leader = ReflectionShopSupport.invokeNoArg(nation, "getKing", "getLeader", "getCapitalMayor");
        Object towns = ReflectionShopSupport.invokeNoArg(nation, "getTowns");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nation_uuid", uuid.toString());
        row.put("nation_name", name);
        row.put("town_count", ReflectionShopSupport.asCollection(towns).size());
        if (leader != null) {
            row.put("leader_uuid", uuidString(ReflectionShopSupport.invokeNoArg(leader, "getUUID")));
            row.put("leader_name", stringOrNull(ReflectionShopSupport.invokeNoArg(leader, "getName")));
        }
        return row;
    }

    private static boolean isCapitalTown(Object town, Object nation) {
        Object capital = ReflectionShopSupport.invokeNoArg(nation, "getCapital");
        if (capital == null || town == null) {
            return false;
        }
        String a = stringOrNull(ReflectionShopSupport.invokeNoArg(town, "getName"));
        String b = stringOrNull(ReflectionShopSupport.invokeNoArg(capital, "getName"));
        return a != null && a.equalsIgnoreCase(b);
    }

    private static String stringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static UUID uuidOrNull(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        String s = stringOrNull(value);
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String uuidString(Object value) {
        UUID uuid = uuidOrNull(value);
        return uuid == null ? null : uuid.toString();
    }

    private static int intOrZero(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
