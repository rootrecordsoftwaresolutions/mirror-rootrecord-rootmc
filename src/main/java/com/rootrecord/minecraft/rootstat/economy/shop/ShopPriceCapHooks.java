package com.rootrecord.minecraft.rootstat.economy.shop;

import com.rootrecord.minecraft.rootstat.RootStatBridge;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Applies the Root Shops average price cap to ChestShop creation when that plugin is present.
 * Uses reflection so ChestShop is not a compile dependency.
 */
public final class ShopPriceCapHooks implements Listener {

    private final RootStatBridge bridge;

    public ShopPriceCapHooks(RootStatBridge bridge) {
        this.bridge = bridge;
    }

    public void register() {
        if (!bridge.config().isEconomyEnabled() || !bridge.config().isEconomyEnforcePriceCap()) {
            return;
        }
        registerChestShopHook();
    }

    private void registerChestShopHook() {
        try {
            Class<? extends Event> eventClass = eventClass(
                    "com.Acrobot.ChestShop.Events.PreShopCreationEvent",
                    "com.acrobot.chestshop.events.PreShopCreationEvent");
            if (eventClass == null) {
                return;
            }
            EventExecutor executor = (listener, event) -> handleChestShopPreCreate(event);
            PluginManager pm = bridge.getPlugin().getServer().getPluginManager();
            pm.registerEvent(eventClass, this, EventPriority.HIGH, executor, bridge.getPlugin(), false);
            bridge.getPlugin().getLogger().info("Root Shops price cap hooked into ChestShop creation events.");
        } catch (Exception ex) {
            bridge.getPlugin().getLogger().log(Level.FINE, "ChestShop price-cap hook unavailable: " + ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Event> eventClass(String... classNames) {
        for (String className : classNames) {
            try {
                Class<?> type = Class.forName(className);
                if (Event.class.isAssignableFrom(type)) {
                    return (Class<? extends Event>) type;
                }
            } catch (ClassNotFoundException ignored) {
                // try next
            }
        }
        return null;
    }

    private void handleChestShopPreCreate(Event event) {
        if (!Bukkit.isPrimaryThread()) {
            return;
        }
        try {
            Object item = ReflectionShopSupport.invokeNoArg(event, "getItemStack", "getItem");
            String itemKey = ShopItemKeys.fromObject(item);
            Object priceObj = ReflectionShopSupport.invokeNoArg(event, "getPrice");
            if (itemKey == null || !(priceObj instanceof Number priceNumber)) {
                return;
            }
            double price = priceNumber.doubleValue();
            double max = bridge.economy().priceRegistry()
                    .maxAllowedPrice(itemKey, bridge.config().economyPriceCapPercentOverAvg());
            if (price <= max) {
                return;
            }
            Method setCancelled = event.getClass().getMethod("setCancelled", boolean.class);
            setCancelled.invoke(event, true);
            Object player = ReflectionShopSupport.invokeNoArg(event, "getPlayer");
            if (player instanceof org.bukkit.entity.Player bukkitPlayer) {
                double avg = bridge.economy().priceRegistry().averagePrice(itemKey);
                bukkitPlayer.sendMessage(bridge.colorize(bridge.msg("shop-price-too-high")
                        .replace("{item}", itemKey)
                        .replace("{price}", String.format("%.3f", price))
                        .replace("{max}", String.format("%.3f", max))
                        .replace("{avg}", String.format("%.3f", avg))));
            }
        } catch (ReflectiveOperationException ex) {
            bridge.getPlugin().getLogger().log(Level.FINE, "ChestShop price-cap handler skipped: " + ex.getMessage());
        }
    }
}
