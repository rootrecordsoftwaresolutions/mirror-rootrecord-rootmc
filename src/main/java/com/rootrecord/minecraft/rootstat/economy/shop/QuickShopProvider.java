package com.rootrecord.minecraft.rootstat.economy.shop;

import com.rootrecord.minecraft.rootstat.economy.EconomySnapshot;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

public final class QuickShopProvider implements ShopProvider {

    private final Logger logger;
    private volatile boolean available;

    public QuickShopProvider(Logger logger) {
        this.logger = logger;
    }

    @Override
    public String id() {
        return ShopProviderIds.QUICKSHOP;
    }

    @Override
    public String displayName() {
        return "QuickShop / QuickShop-Hikari";
    }

    @Override
    public boolean requiresMainThread() {
        return true;
    }

    @Override
    public boolean probe() {
        available = resolveShopManager() != null;
        return available;
    }

    @Override
    public List<EconomySnapshot.ShopListingRow> collectListings() {
        if (!available) {
            return List.of();
        }
        Object shopManager = resolveShopManager();
        if (shopManager == null) {
            return List.of();
        }
        Collection<Object> shops = loadShops(shopManager);
        List<EconomySnapshot.ShopListingRow> out = new ArrayList<>();
        for (Object shop : shops) {
            EconomySnapshot.ShopListingRow row = mapShop(shop);
            if (row != null) {
                out.add(row);
            }
        }
        return out;
    }

    private Object resolveShopManager() {
        try {
            Plugin plugin = ReflectionShopSupport.pluginByNames("QuickShop", "QuickShop-Hikari");
            if (plugin == null) {
                return null;
            }
            Object manager = ReflectionShopSupport.invokeNoArg(plugin, "getShopManager");
            if (manager != null) {
                return manager;
            }
            ClassLoader loader = plugin.getClass().getClassLoader();
            Class<?> apiClass = Class.forName("com.ghostchu.quickshop.api.QuickShopAPI", false, loader);
            Object api = apiClass.getMethod("getInstance").invoke(null);
            return ReflectionShopSupport.invokeNoArg(api, "getShopManager");
        } catch (Throwable ex) {
            ReflectionShopSupport.logProbeFailure(logger, id(), ex);
            return null;
        }
    }

    private Collection<Object> loadShops(Object shopManager) {
        Object loaded = ReflectionShopSupport.invokeNoArg(
                shopManager,
                "getAllShops",
                "getLoadedShops",
                "getShops",
                "getAllLoadedShops");
        if (loaded == null) {
            loaded = ReflectionShopSupport.invoke(shopManager, "getAllShops", new Class<?>[] { boolean.class }, false);
        }
        return ReflectionShopSupport.asCollection(loaded);
    }

    private EconomySnapshot.ShopListingRow mapShop(Object shop) {
        Location location = ReflectionShopSupport.locationFromShop(shop);
        if (location == null || location.getWorld() == null) {
            return null;
        }
        String itemKey = ShopItemKeys.fromObject(ReflectionShopSupport.itemFromShop(shop));
        double price = ReflectionShopSupport.priceFromShop(shop);
        if (itemKey == null || price <= 0 || !Double.isFinite(price)) {
            return null;
        }
        String listingType = ReflectionShopSupport.isBuyShop(shop) ? "buy" : "sell";
        String ownerUuid = ReflectionShopSupport.ownerUuid(shop);
        String ownerName = ReflectionShopSupport.ownerName(shop);
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
}
