package com.rootrecord.minecraft.rootstat.economy;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import com.rootrecord.minecraft.rootstat.economy.shop.ShopItemKeys;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SignShopScanner {

    private static final Pattern BRACKET_ITEM = Pattern.compile("\\[([^\\]]+)\\]");
    private static final Pattern PRICE_TOKEN = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");

    public List<EconomySnapshot.ShopListingRow> scanLoadedShopListings() {
        List<EconomySnapshot.ShopListingRow> out = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                try {
                    for (BlockState state : chunk.getTileEntities()) {
                        if (!(state instanceof Sign sign)) {
                            continue;
                        }
                        collectFromSign(sign, world.getName(), out);
                    }
                } catch (IllegalStateException ignored) {
                    // Paper may reject tile entities without loaded block data in this chunk slice.
                }
            }
        }
        return out;
    }

    public List<EconomySnapshot.ShopPriceRow> aggregatePrices(List<EconomySnapshot.ShopListingRow> listings) {
        Map<String, List<Double>> pricesByItem = new LinkedHashMap<>();
        for (EconomySnapshot.ShopListingRow listing : listings) {
            if (countsTowardMarketAverage(listing)) {
                pricesByItem.computeIfAbsent(listing.itemKey(), ignored -> new ArrayList<>()).add(listing.price());
            }
        }
        List<EconomySnapshot.ShopPriceRow> out = new ArrayList<>();
        for (var entry : pricesByItem.entrySet()) {
            out.add(new EconomySnapshot.ShopPriceRow(entry.getKey(), entry.getValue(), "sign_scan"));
        }
        return out;
    }

    private void collectFromSign(Sign sign, String worldName, List<EconomySnapshot.ShopListingRow> out) {
        String ownerUsername = null;
        String itemKey = null;
        List<Double> prices = new ArrayList<>();
        String listingType = "sell";

        for (Side side : new Side[] { Side.FRONT, Side.BACK }) {
            String[] lines = sign.getSide(side).getLines();
            for (int i = 0; i < lines.length; i++) {
                String rawLine = lines[i];
                if (rawLine == null || rawLine.isBlank()) {
                    continue;
                }
                String line = rawLine.trim();
                if (i == 0 && ownerUsername == null) {
                    ownerUsername = parseOwnerLine(line);
                }
                Matcher bracket = BRACKET_ITEM.matcher(line);
                if (bracket.find()) {
                    itemKey = normalizeItemKey(bracket.group(1));
                }
                if (line.toLowerCase(Locale.ROOT).contains("buy")) {
                    listingType = "buy";
                }
                for (String token : line.replace(",", " ").split("\\s+")) {
                    if (!token.contains(".") && !token.chars().allMatch(Character::isDigit)) {
                        continue;
                    }
                    Matcher price = PRICE_TOKEN.matcher(token);
                    if (price.find()) {
                        try {
                            double value = Double.parseDouble(price.group(1));
                            if (value > 0) {
                                prices.add(value);
                            }
                        } catch (NumberFormatException ignored) {
                            // skip
                        }
                    }
                }
            }
        }

        if (itemKey == null || prices.isEmpty()) {
            return;
        }

        double price = prices.get(0);
        String ownerUuid = resolveUuid(ownerUsername);
        String shopId = worldName + ":" + sign.getX() + ":" + sign.getY() + ":" + sign.getZ();
        out.add(new EconomySnapshot.ShopListingRow(
                shopId,
                ownerUuid,
                ownerUsername,
                worldName,
                sign.getX(),
                sign.getY(),
                sign.getZ(),
                itemKey,
                price,
                listingType,
                -1));
    }

    /** Out-of-stock sell listings must not drag down /value medians. */
    static boolean countsTowardMarketAverage(EconomySnapshot.ShopListingRow listing) {
        if (listing == null || listing.price() <= 0) {
            return false;
        }
        if ("buy".equalsIgnoreCase(listing.listingType())) {
            return true;
        }
        // stockQuantity < 0 = unknown (legacy sign scan) — still counts
        return listing.stockQuantity() != 0;
    }

    static ParsedSignShop parseSignLines(String[] lines) {
        String ownerUsername = null;
        String itemKey = null;
        Double price = null;
        String listingType = "sell";
        if (lines == null) {
            return new ParsedSignShop(null, null, null, listingType);
        }
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.isBlank()) {
                continue;
            }
            if (i == 0 && ownerUsername == null) {
                ownerUsername = parseOwnerLine(line);
            }
            Matcher bracket = BRACKET_ITEM.matcher(line);
            if (bracket.find()) {
                itemKey = normalizeItemKey(bracket.group(1));
            }
            if (line.toLowerCase(Locale.ROOT).contains("buy")) {
                listingType = "buy";
            }
            for (String token : line.replace(",", " ").split("\\s+")) {
                Matcher priceMatch = PRICE_TOKEN.matcher(token);
                if (priceMatch.find()) {
                    try {
                        double value = Double.parseDouble(priceMatch.group(1));
                        if (value > 0) {
                            price = value;
                        }
                    } catch (NumberFormatException ignored) {
                        // skip
                    }
                }
            }
        }
        return new ParsedSignShop(ownerUsername, itemKey, price, listingType);
    }

    private static String parseOwnerLine(String line) {
        String candidate = line.startsWith("@") ? line.substring(1).trim() : line.trim();
        if (USERNAME.matcher(candidate).matches()) {
            return candidate;
        }
        return null;
    }

    private static String resolveUuid(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        try {
            OfflinePlayer player = Bukkit.getOfflinePlayer(username);
            if (player.getUniqueId() != null) {
                return player.getUniqueId().toString();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private static String normalizeItemKey(String raw) {
        String key = ShopItemKeys.fromMaterialName(raw);
        return key == null ? "" : key;
    }

    public record ParsedSignShop(String ownerUsername, String itemKey, Double price, String listingType) {}
}
