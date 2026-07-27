package com.rootrecord.minecraft.rootstat.economy.shop;

import com.rootrecord.minecraft.rootstat.economy.EconomySnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

public final class ChestShopProvider implements ShopProvider {

    private final Logger logger;
    private volatile boolean available;

    public ChestShopProvider(Logger logger) {
        this.logger = logger;
    }

    @Override
    public String id() {
        return ShopProviderIds.CHESTSHOP;
    }

    @Override
    public String displayName() {
        return "ChestShop";
    }

    @Override
    public boolean probe() {
        available = ReflectionShopSupport.pluginByNames("ChestShop") != null && resolveShopSource() != null;
        return available;
    }

    @Override
    public List<EconomySnapshot.ShopListingRow> collectListings() {
        if (!available) {
            return List.of();
        }
        Object source = resolveShopSource();
        if (source == null) {
            return List.of();
        }
        Collection<Object> shops = loadShops(source);
        List<EconomySnapshot.ShopListingRow> out = new ArrayList<>();
        for (Object shop : shops) {
            EconomySnapshot.ShopListingRow row = mapShop(shop);
            if (row != null) {
                out.add(row);
            }
        }
        return out;
    }

    private Object resolveShopSource() {
        try {
            Plugin plugin = ReflectionShopSupport.pluginByNames("ChestShop");
            if (plugin == null) {
                return null;
            }
            Object manager = ReflectionShopSupport.invokeNoArg(plugin, "getShopManager", "getShopDatabase");
            if (manager != null) {
                return manager;
            }
            for (String className : List.of(
                    "com.Acrobot.ChestShop.ChestShop",
                    "com.acrobot.chestshop.ChestShop")) {
                try {
                    Class<?> type = Class.forName(className);
                    Object instance = type.getMethod("getPlugin").invoke(null);
                    Object fromStatic = ReflectionShopSupport.invokeNoArg(instance, "getShopManager", "getShopDatabase");
                    if (fromStatic != null) {
                        return fromStatic;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // try next
                }
            }
            return plugin;
        } catch (Throwable ex) {
            ReflectionShopSupport.logProbeFailure(logger, id(), ex);
            return null;
        }
    }

    private Collection<Object> loadShops(Object source) {
        Object loaded = ReflectionShopSupport.invokeNoArg(
                source,
                "getAllShops",
                "getShops",
                "getLoadedShops",
                "getShopList");
        if (loaded == null) {
            loaded = ReflectionShopSupport.invokeNoArg(Bukkit.getPluginManager().getPlugin("ChestShop"), "getShops");
        }
        if (loaded instanceof Object[] array) {
            return List.of(array);
        }
        return ReflectionShopSupport.asCollection(loaded);
    }

    private EconomySnapshot.ShopListingRow mapShop(Object shop) {
        Location location = ReflectionShopSupport.locationFromShop(shop);
        if (location == null) {
            location = locationFromChestShop(shop);
        }
        if (location == null || location.getWorld() == null) {
            return null;
        }
        Object item = ReflectionShopSupport.itemFromShop(shop);
        if (item == null) {
            item = ReflectionShopSupport.invokeNoArg(shop, "getProduct", "getItemStack");
        }
        String itemKey = ShopItemKeys.fromObject(item);
        if (itemKey == null) {
            Object materialName = ReflectionShopSupport.invokeNoArg(shop, "getItemId", "getMaterial");
            itemKey = ShopItemKeys.fromObject(materialName);
        }
        double price = ReflectionShopSupport.priceFromShop(shop);
        if (price <= 0) {
            Object buy = ReflectionShopSupport.invokeNoArg(shop, "getBuyPrice");
            Object sell = ReflectionShopSupport.invokeNoArg(shop, "getSellPrice");
            if (sell instanceof Number sellNum && sellNum.doubleValue() > 0) {
                price = sellNum.doubleValue();
            } else if (buy instanceof Number buyNum && buyNum.doubleValue() > 0) {
                price = buyNum.doubleValue();
            }
        }
        if (itemKey == null || price <= 0 || !Double.isFinite(price)) {
            return null;
        }
        String listingType = "sell";
        Object buyPrice = ReflectionShopSupport.invokeNoArg(shop, "getBuyPrice");
        if (buyPrice instanceof Number buy && buy.doubleValue() > 0) {
            listingType = "buy";
        }
        String ownerUuid = ReflectionShopSupport.ownerUuid(shop);
        String ownerName = ReflectionShopSupport.ownerName(shop);
        if (ownerName == null) {
            Object owner = ReflectionShopSupport.invokeNoArg(shop, "getOwnerName", "getOwner");
            if (owner instanceof String name) {
                ownerName = name;
            }
        }
        return new EconomySnapshot.ShopListingRow(
                ReflectionShopSupport.shopId(id(), location),
                ownerUuid,
                ownerName,
                location.getWorld().getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ(),
                itemKey,
                price,
                listingType,
                0);
    }

    private Location locationFromChestShop(Object shop) {
        Object world = ReflectionShopSupport.invokeNoArg(shop, "getWorld");
        Object x = ReflectionShopSupport.invokeNoArg(shop, "getX");
        Object y = ReflectionShopSupport.invokeNoArg(shop, "getY");
        Object z = ReflectionShopSupport.invokeNoArg(shop, "getZ");
        if (world instanceof String worldName && x instanceof Number nx && y instanceof Number ny && z instanceof Number nz) {
            return new Location(Bukkit.getWorld(worldName), nx.doubleValue(), ny.doubleValue(), nz.doubleValue());
        }
        return null;
    }
}
