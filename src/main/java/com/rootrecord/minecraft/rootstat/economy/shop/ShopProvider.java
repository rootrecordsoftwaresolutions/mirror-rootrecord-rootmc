package com.rootrecord.minecraft.rootstat.economy.shop;

import com.rootrecord.minecraft.rootstat.economy.EconomySnapshot;

import java.util.List;

/** Reads shop listings from an optional third-party shop plugin or sign fallback. */
public interface ShopProvider {

    String id();

    String displayName();

    /** True when the backing plugin/API is present and usable. */
    boolean probe();

    /** Whether collection must run on the server main thread. */
    default boolean requiresMainThread() {
        return false;
    }

    List<EconomySnapshot.ShopListingRow> collectListings();
}
