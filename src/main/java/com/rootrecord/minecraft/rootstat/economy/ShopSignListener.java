package com.rootrecord.minecraft.rootstat.economy;

import com.rootrecord.minecraft.rootstat.RootStatBridge;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

public final class ShopSignListener implements Listener {

    private final RootStatBridge bridge;

    public ShopSignListener(RootStatBridge bridge) {
        this.bridge = bridge;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        if (!bridge.config().isEconomyEnabled() || !bridge.config().isEconomyEnforcePriceCap()) {
            return;
        }
        SignShopScanner.ParsedSignShop parsed = SignShopScanner.parseSignLines(event.getLines());
        if (parsed.itemKey() == null || parsed.price() == null) {
            return;
        }
        double max = bridge.economy().priceRegistry()
                .maxAllowedPrice(parsed.itemKey(), bridge.config().economyPriceCapPercentOverAvg());
        if (parsed.price() > max) {
            Player player = event.getPlayer();
            event.setCancelled(true);
            double avg = bridge.economy().priceRegistry().averagePrice(parsed.itemKey());
            player.sendMessage(bridge.colorize(bridge.msg("shop-price-too-high")
                    .replace("{item}", parsed.itemKey())
                    .replace("{price}", String.format("%.3f", parsed.price()))
                    .replace("{max}", String.format("%.3f", max))
                    .replace("{avg}", String.format("%.3f", avg))));
        }
    }
}
