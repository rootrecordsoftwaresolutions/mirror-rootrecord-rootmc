package com.rootrecord.minecraft.rootstat.economy;

import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

/** Mint peg for physical gold items (matches Root Essentials /mint rates). */
public final class MintPegGold {

    private MintPegGold() {}

    public static double stackGoldG(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return 0;
        }
        Double each = mintRate(stack.getType());
        if (each == null) {
            return nestedContainerGoldG(stack);
        }
        return each * stack.getAmount();
    }

    public static double contentsGoldG(ItemStack[] contents) {
        if (contents == null) {
            return 0;
        }
        double total = 0;
        for (ItemStack stack : contents) {
            total += stackGoldG(stack);
        }
        return total;
    }

    public static double inventoryGoldG(Inventory inventory) {
        return inventory == null ? 0 : contentsGoldG(inventory.getContents());
    }

    public static double nestedContainerGoldG(ItemStack stack) {
        if (stack == null || !stack.getType().name().endsWith("SHULKER_BOX")) {
            return 0;
        }
        if (!(stack.getItemMeta() instanceof BlockStateMeta meta)) {
            return 0;
        }
        if (!(meta.getBlockState() instanceof ShulkerBox shulker)) {
            return 0;
        }
        return contentsGoldG(shulker.getInventory().getContents());
    }

    public static Double mintRate(Material material) {
        if (material == null) {
            return null;
        }
        return switch (material) {
            case GOLD_NUGGET -> 1.0 / 9.0;
            case RAW_GOLD, GOLD_INGOT -> 1.0;
            case GOLD_BLOCK -> 9.0;
            default -> null;
        };
    }
}
