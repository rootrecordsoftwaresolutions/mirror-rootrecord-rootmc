package com.rootrecord.minecraft.rootstat.economy.shop;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.Locale;
import java.util.Map;

public final class ShopItemKeys {

    private static final String BOOK = "ENCHANTED_BOOK";
    private static final String BOOK_PREFIX = BOOK + "_";

    private ShopItemKeys() {}

    public static String fromMaterialName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            Material material = Material.matchMaterial(cleaned);
            if (material != null && material.isItem()) {
                return material.name();
            }
        } catch (Exception ignored) {
            // fall through
        }
        String normalized = cleaned.replaceAll("[^A-Z0-9_]", "");
        return normalized.isBlank() ? null : normalized;
    }

    public static String fromItemStack(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        if (stack.getType() == Material.ENCHANTED_BOOK) {
            String bookKey = enchantedBookKey(stack);
            if (bookKey != null) {
                return bookKey;
            }
        }
        if (isPotionMaterial(stack.getType())) {
            String potionKey = potionKey(stack);
            if (potionKey != null) {
                return potionKey;
            }
        }
        return stack.getType().name();
    }

    private static boolean isPotionMaterial(Material material) {
        return material == Material.POTION
                || material == Material.SPLASH_POTION
                || material == Material.LINGERING_POTION
                || material == Material.TIPPED_ARROW;
    }

    private static String potionKey(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof PotionMeta potion)) {
            return stack.getType().name();
        }
        PotionType type = potion.getBasePotionType();
        if (type == null) {
            return stack.getType().name();
        }
        return stack.getType().name() + "_" + type.name();
    }

    private static String enchantedBookKey(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof EnchantmentStorageMeta storage)) {
            return null;
        }
        Map<Enchantment, Integer> stored = storage.getStoredEnchants();
        if (stored.isEmpty() || stored.size() != 1) {
            return BOOK;
        }
        Map.Entry<Enchantment, Integer> entry = stored.entrySet().iterator().next();
        return BOOK_PREFIX + enchantPart(entry.getKey()) + "_" + entry.getValue();
    }

    private static String enchantPart(Enchantment enchantment) {
        return enchantment.getKey().getKey().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    public static String fromObject(Object itemLike) {
        if (itemLike == null) {
            return null;
        }
        if (itemLike instanceof ItemStack stack) {
            return fromItemStack(stack);
        }
        try {
            Object type = itemLike.getClass().getMethod("getType").invoke(itemLike);
            if (type instanceof Material material) {
                return material.isItem() ? material.name() : null;
            }
        } catch (ReflectiveOperationException ignored) {
            // fall through
        }
        return fromMaterialName(String.valueOf(itemLike));
    }
}
