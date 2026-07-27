package com.rootrecord.minecraft.rootstat.economy;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public record EconomySnapshot(
        List<ShopPriceRow> shopPrices,
        List<ShopListingRow> shopListings,
        List<BalanceRow> balances,
        List<SystemBalanceRow> systemBalances,
        List<PlayerItemsRow> playerItems,
        Map<String, Integer> serverItems,
        List<TreasuryLedgerRow> treasuryLedger,
        List<PlaytimeMonthlyRow> playtimeMonthly,
        List<TownTaxRow> townTaxRates,
        List<GoldFoundRow> goldFound,
        List<GoldItemEventRow> goldItemEvents,
        double treasuryBalance) {

    public EconomySnapshot {
        shopPrices = shopPrices == null ? List.of() : List.copyOf(shopPrices);
        shopListings = shopListings == null ? List.of() : List.copyOf(shopListings);
        balances = balances == null ? List.of() : List.copyOf(balances);
        systemBalances = systemBalances == null ? List.of() : List.copyOf(systemBalances);
        playerItems = playerItems == null ? List.of() : List.copyOf(playerItems);
        serverItems = serverItems == null ? Map.of() : Map.copyOf(serverItems);
        treasuryLedger = treasuryLedger == null ? List.of() : List.copyOf(treasuryLedger);
        playtimeMonthly = playtimeMonthly == null ? List.of() : List.copyOf(playtimeMonthly);
        townTaxRates = townTaxRates == null ? List.of() : List.copyOf(townTaxRates);
        goldFound = goldFound == null ? List.of() : List.copyOf(goldFound);
        goldItemEvents = goldItemEvents == null ? List.of() : List.copyOf(goldItemEvents);
    }

    public static EconomySnapshot empty() {
        return new EconomySnapshot(List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0);
    }

    public record ShopPriceRow(String itemKey, List<Double> prices, String source) {
        public ShopPriceRow {
            prices = prices == null ? List.of() : List.copyOf(prices);
        }
    }

    public record ShopListingRow(
            String shopId,
            String ownerUuid,
            String ownerUsername,
            String worldName,
            int x,
            int y,
            int z,
            String itemKey,
            double price,
            String listingType,
            int stockQuantity) {}

    public record BalanceRow(String uuid, String username, double balance, String currency) {}

    public record SystemBalanceRow(String uuid, String username, double balance, String accountType, String currency) {}

    public record PlayerItemsRow(String uuid, String username, Map<String, Integer> items, String source) {
        public PlayerItemsRow {
            items = items == null ? Map.of() : Collections.unmodifiableMap(items);
        }
    }

    public record TreasuryLedgerRow(
            long mysqlId,
            String entryType,
            double amount,
            String fromUuid,
            String toUuid,
            String details,
            String createdAt) {}

    public record PlaytimeMonthlyRow(String uuid, String monthKey, long playtimeSeconds) {}

    public record TownTaxRow(String townName, String mayorName, double taxPercent) {}

    public record GoldFoundRow(
            String uuid,
            String username,
            double totalGoldG,
            double minedOreG,
            double minedBlockG,
            double lootChestG,
            double lootMobG,
            double pickupG,
            int findEvents) {}

    public record GoldItemEventRow(
            long eventId,
            String uuid,
            String username,
            String eventType,
            String obtainedVia,
            String material,
            int stackAmount,
            double goldG,
            String world,
            Integer blockX,
            Integer blockY,
            Integer blockZ,
            String contextJson,
            String createdAt) {}
}
