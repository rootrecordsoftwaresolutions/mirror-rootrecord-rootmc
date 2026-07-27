package com.rootrecord.minecraft.rootstat.economy.shop;

import com.rootrecord.minecraft.rootstat.economy.EconomySnapshot;
import com.rootrecord.minecraft.rootstat.economy.SignShopScanner;

import java.util.List;

public final class SignShopProvider implements ShopProvider {

    private final SignShopScanner scanner = new SignShopScanner();

    @Override
    public String id() {
        return ShopProviderIds.SIGN;
    }

    @Override
    public String displayName() {
        return "Sign shops (fallback)";
    }

    @Override
    public boolean probe() {
        return true;
    }

    @Override
    public boolean requiresMainThread() {
        return true;
    }

    @Override
    public List<EconomySnapshot.ShopListingRow> collectListings() {
        return scanner.scanLoadedShopListings().stream()
                .map(row -> new EconomySnapshot.ShopListingRow(
                        ShopProviderIds.SIGN + ":" + row.shopId(),
                        row.ownerUuid(),
                        row.ownerUsername(),
                        row.worldName(),
                        row.x(),
                        row.y(),
                        row.z(),
                        row.itemKey(),
                        row.price(),
                        row.listingType(),
                        row.stockQuantity()))
                .toList();
    }
}
