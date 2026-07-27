package com.rootrecord.minecraft.rootstat.economy;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Mint-peg gold split by redeemable item class (nugget / ingot / block / other). */
public final class GoldBreakdown {

    public double nuggetG;
    public double ingotG;
    public double blockG;
    public double otherG;

    public GoldBreakdown() {}

    public GoldBreakdown(double nuggetG, double ingotG, double blockG, double otherG) {
        this.nuggetG = nuggetG;
        this.ingotG = ingotG;
        this.blockG = blockG;
        this.otherG = otherG;
    }

    public double totalG() {
        return nuggetG + ingotG + blockG + otherG;
    }

    public void add(GoldBreakdown other) {
        if (other == null) {
            return;
        }
        nuggetG += other.nuggetG;
        ingotG += other.ingotG;
        blockG += other.blockG;
        otherG += other.otherG;
    }

    public void addStack(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return;
        }
        Double each = MintPegGold.mintRate(stack.getType());
        if (each != null) {
            double g = each * stack.getAmount();
            switch (stack.getType()) {
                case GOLD_NUGGET -> nuggetG += g;
                case RAW_GOLD, GOLD_INGOT -> ingotG += g;
                case GOLD_BLOCK -> blockG += g;
                default -> otherG += g;
            }
            return;
        }
        double nested = MintPegGold.nestedContainerGoldG(stack);
        if (nested > 0.0001) {
            otherG += nested;
        }
    }

    public void addContents(ItemStack[] contents) {
        if (contents == null) {
            return;
        }
        for (ItemStack stack : contents) {
            addStack(stack);
        }
    }

    public void addInventory(org.bukkit.inventory.Inventory inventory) {
        if (inventory == null) {
            return;
        }
        addContents(inventory.getContents());
    }

    public void addPlacedBlock(Material type) {
        if (type == Material.GOLD_BLOCK) {
            blockG += 9.0;
        }
    }
}
