package com.rootrecord.minecraft.rootstat.economy;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PlayerInventoryScanner {

    public List<EconomySnapshot.PlayerItemsRow> scanOnlinePlayers() {
        return org.bukkit.Bukkit.getOnlinePlayers().stream()
                .map(this::scanPlayer)
                .filter(row -> !row.items().isEmpty())
                .toList();
    }

    private EconomySnapshot.PlayerItemsRow scanPlayer(Player player) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        mergeInventory(merged, player.getInventory().getContents());
        mergeInventory(merged, player.getEnderChest().getContents());
        return new EconomySnapshot.PlayerItemsRow(
                player.getUniqueId().toString(),
                player.getName(),
                merged,
                "inventory");
    }

    private void mergeInventory(Map<String, Integer> out, ItemStack[] contents) {
        if (contents == null) {
            return;
        }
        for (ItemStack stack : contents) {
            if (stack == null || stack.getType() == Material.AIR) {
                continue;
            }
            String key = stack.getType().name();
            out.merge(key, stack.getAmount(), Integer::sum);
        }
    }
}
