package com.rootrecord.minecraft.rootstat.economy;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ChunkChestScanner {

    private final int maxChunksPerSync;
    private int chunkCursor;

    public ChunkChestScanner(int maxChunksPerSync) {
        this.maxChunksPerSync = Math.max(1, maxChunksPerSync);
    }

    public Map<String, Integer> scanLoadedContainers() {
        Map<String, Integer> totals = new LinkedHashMap<>();
        Chunk[] loaded = collectLoadedChunks();
        if (loaded.length == 0) {
            return totals;
        }

        int scanned = 0;
        while (scanned < maxChunksPerSync) {
            if (chunkCursor >= loaded.length) {
                chunkCursor = 0;
            }
            Chunk chunk = loaded[chunkCursor++];
            try {
                for (BlockState state : chunk.getTileEntities()) {
                    if (!(state instanceof Container container)) {
                        continue;
                    }
                    Material type = state.getType();
                    if (!isStorageContainer(type)) {
                        continue;
                    }
                    mergeInventory(totals, container.getInventory());
                }
            } catch (IllegalStateException ignored) {
                // Paper may reject tile entities without loaded block data in this chunk slice.
            }
            scanned++;
        }
        return totals;
    }

    private boolean isStorageContainer(Material type) {
        if (type == null) {
            return false;
        }
        String name = type.name();
        return name.endsWith("_CHEST")
                || name.endsWith("_BARREL")
                || type == Material.SHULKER_BOX
                || name.endsWith("_SHULKER_BOX");
    }

    private Chunk[] collectLoadedChunks() {
        int count = 0;
        for (World world : Bukkit.getWorlds()) {
            count += world.getLoadedChunks().length;
        }
        Chunk[] out = new Chunk[count];
        int idx = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                out[idx++] = chunk;
            }
        }
        return out;
    }

    private void mergeInventory(Map<String, Integer> out, Inventory inventory) {
        if (inventory == null) {
            return;
        }
        for (ItemStack stack : inventory.getContents()) {
            if (stack == null || stack.getType() == Material.AIR) {
                continue;
            }
            out.merge(stack.getType().name(), stack.getAmount(), Integer::sum);
        }
    }
}
