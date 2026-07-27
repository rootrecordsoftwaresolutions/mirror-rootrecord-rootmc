package com.rootrecord.minecraft.rootstat.economy.shop;

import com.rootrecord.minecraft.rootstat.RootStatBridge;
import com.rootrecord.minecraft.rootstat.config.RootStatConfig;
import com.rootrecord.minecraft.rootstat.economy.EconomySnapshot;
import com.rootrecord.minecraft.rootstat.economy.SignShopScanner;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class ShopListingService {

    private final RootStatBridge bridge;
    private final Logger logger;
    private final List<ShopProvider> providers;
    private volatile String lastActiveSummary = "none";

    public ShopListingService(RootStatBridge bridge) {
        this.bridge = bridge;
        this.logger = bridge.getPlugin().getLogger();
        this.providers = List.of(
                new RootMcShopsProvider(logger),
                new QuickShopProvider(logger),
                new ChestShopProvider(logger),
                new SignShopProvider());
    }

    public String lastActiveSummary() {
        return lastActiveSummary;
    }

    public List<EconomySnapshot.ShopListingRow> collectListings(RootStatConfig config) {
        List<ShopProvider> selected = selectProviders(config);
        if (selected.isEmpty()) {
            lastActiveSummary = "disabled";
            return List.of();
        }
        List<EconomySnapshot.ShopListingRow> listings = collectOnMainThread(selected);
        lastActiveSummary = selected.stream().map(ShopProvider::id).reduce((a, b) -> a + "+" + b).orElse("none");
        logger.fine("Shop listings collected via [" + lastActiveSummary + "]: " + listings.size());
        return listings;
    }

    public List<EconomySnapshot.ShopPriceRow> aggregatePrices(
            List<EconomySnapshot.ShopListingRow> listings,
            String source) {
        return new SignShopScanner().aggregatePrices(listings).stream()
                .map(row -> new EconomySnapshot.ShopPriceRow(row.itemKey(), row.prices(), source))
                .toList();
    }

    public void logDetectedProviders() {
        try {
            List<String> detected = new ArrayList<>();
            for (ShopProvider provider : providers) {
                try {
                    if (provider.probe()) {
                        detected.add(provider.id());
                    }
                } catch (Throwable ex) {
                    logger.fine("Shop provider " + provider.id() + " probe skipped: " + ex.getMessage());
                }
            }
            String mode = bridge.config().economyShopProvider();
            logger.info("Shop providers detected: "
                    + (detected.isEmpty() ? "none (sign fallback only)" : String.join(", ", detected))
                    + " — mode=" + mode
                    + ", priority=" + String.join(">", bridge.config().economyShopProvidersPriority()));
        } catch (Throwable ex) {
            logger.warning("Shop provider detection skipped: " + ex.getMessage());
        }
    }

    private List<ShopProvider> selectProviders(RootStatConfig config) {
        Map<String, ShopProvider> byId = new LinkedHashMap<>();
        for (ShopProvider provider : providers) {
            byId.put(provider.id(), provider);
        }
        String mode = config.economyShopProvider().toLowerCase(Locale.ROOT);
        if ("sign".equals(mode)) {
            return maybeEnabled(List.of(byId.get(ShopProviderIds.SIGN)), config);
        }
        if (ShopProviderIds.QUICKSHOP.equals(mode) || ShopProviderIds.CHESTSHOP.equals(mode)) {
            ShopProvider forced = byId.get(mode);
            return forced != null && forced.probe() ? List.of(forced) : List.of();
        }
        List<ShopProvider> ordered = new ArrayList<>();
        for (String id : config.economyShopProvidersPriority()) {
            ShopProvider provider = byId.get(id.toLowerCase(Locale.ROOT));
            if (provider != null) {
                ordered.add(provider);
            }
        }
        if ("merge".equals(mode)) {
            return maybeEnabled(ordered.stream().filter(ShopProvider::probe).toList(), config);
        }
        // auto: first probed provider in priority list; sign is last-resort fallback
        for (ShopProvider provider : ordered) {
            if (!provider.id().equals(ShopProviderIds.SIGN) && provider.probe()) {
                return List.of(provider);
            }
        }
        if (config.isEconomyScanSigns() && byId.get(ShopProviderIds.SIGN).probe()) {
            return List.of(byId.get(ShopProviderIds.SIGN));
        }
        return List.of();
    }

    private List<ShopProvider> maybeEnabled(List<ShopProvider> selected, RootStatConfig config) {
        if (!config.isEconomyEnabled()) {
            return List.of();
        }
        if (!config.isEconomyScanSigns() && selected.stream().anyMatch(p -> ShopProviderIds.SIGN.equals(p.id()))) {
            return selected.stream().filter(p -> !ShopProviderIds.SIGN.equals(p.id())).toList();
        }
        return selected;
    }

    private List<EconomySnapshot.ShopListingRow> collectFromProviders(List<ShopProvider> selected) {
        if (selected.size() == 1) {
            return selected.get(0).collectListings();
        }
        Map<String, EconomySnapshot.ShopListingRow> deduped = new LinkedHashMap<>();
        for (ShopProvider provider : selected) {
            for (EconomySnapshot.ShopListingRow row : provider.collectListings()) {
                deduped.putIfAbsent(row.shopId(), row);
            }
        }
        return List.copyOf(deduped.values());
    }

    private List<EconomySnapshot.ShopListingRow> collectOnMainThread(List<ShopProvider> selected) {
        if (Bukkit.isPrimaryThread()) {
            return collectFromProviders(selected);
        }
        AtomicReference<List<EconomySnapshot.ShopListingRow>> result = new AtomicReference<>(List.of());
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        var latch = new java.util.concurrent.CountDownLatch(1);
        Bukkit.getScheduler().runTask(bridge.getPlugin(), () -> {
            try {
                result.set(collectFromProviders(selected));
            } catch (RuntimeException ex) {
                error.set(ex);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return List.of();
        }
        if (error.get() != null) {
            throw error.get();
        }
        return result.get();
    }
}
