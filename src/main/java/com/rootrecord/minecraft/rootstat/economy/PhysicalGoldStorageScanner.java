package com.rootrecord.minecraft.rootstat.economy;

import com.rootrecord.minecraft.rootstat.economy.shop.ReflectionShopSupport;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Periodic scan of player storage for redeemable physical gold at the mint peg. */
public final class PhysicalGoldStorageScanner {

    public record PlayerRow(
            String uuid,
            String username,
            double inventoryG,
            double enderG,
            double shopG,
            double chestG,
            double townyPlacedG,
            double totalG,
            GoldBreakdown inventory,
            GoldBreakdown ender,
            GoldBreakdown chest,
            GoldBreakdown shop,
            GoldBreakdown towny) {}

    public record ScanResult(
            List<PlayerRow> players,
            double unattributedG,
            GoldBreakdown unattributed,
            double totalG,
            int shopsScanned,
            int chunksScanned,
            int townyBlocksScanned) {

        public ScanResult {
            players = players == null ? List.of() : List.copyOf(players);
            if (unattributed == null) {
                unattributed = new GoldBreakdown();
            }
        }
    }

    private final int maxChunksPerScan;
    private final int maxShopsPerScan;
    private int chunkCursor;
    private int shopCursor;

    public PhysicalGoldStorageScanner(int maxChunksPerScan, int maxShopsPerScan) {
        this.maxChunksPerScan = Math.max(1, maxChunksPerScan);
        this.maxShopsPerScan = Math.max(1, maxShopsPerScan);
    }

    public ScanResult scan() {
        Map<String, MutableRow> byUuid = new LinkedHashMap<>();
        GoldBreakdown unattributed = new GoldBreakdown();

        for (Player player : Bukkit.getOnlinePlayers()) {
            String uuid = player.getUniqueId().toString();
            MutableRow row = byUuid.computeIfAbsent(uuid, k -> new MutableRow(uuid, player.getName()));
            row.username = player.getName();
            row.inventory.addContents(player.getInventory().getStorageContents());
            row.inventory.addContents(player.getInventory().getArmorContents());
            row.inventory.addContents(player.getInventory().getExtraContents());
            row.ender.addInventory(player.getEnderChest());
        }

        Set<String> shopBlocks = new HashSet<>();
        GoldBreakdown shopUnattributed = new GoldBreakdown();
        int shopsScanned = scanShopContainers(byUuid, shopBlocks, shopUnattributed);
        unattributed.add(shopUnattributed);
        unattributed.add(scanWorldContainers(byUuid, shopBlocks));
        int chunksScanned = lastChunksScanned;
        int townyBlocksScanned = lastTownyBlocksScanned;

        List<PlayerRow> rows = new ArrayList<>();
        double total = unattributed.totalG();
        for (MutableRow row : byUuid.values()) {
            row.recomputeTotals();
            if (row.totalG <= 0.0001) {
                continue;
            }
            total += row.totalG;
            rows.add(row.freeze());
        }
        return new ScanResult(rows, unattributed.totalG(), unattributed, total, shopsScanned, chunksScanned, townyBlocksScanned);
    }

    private int lastChunksScanned;
    private int lastTownyBlocksScanned;

    private int scanShopContainers(
            Map<String, MutableRow> byUuid, Set<String> shopBlocks, GoldBreakdown unattributedOut) {
        Plugin shops = ReflectionShopSupport.pluginByNames("Root-ChestShops", "RootMC-Shops");
        if (shops == null) {
            return 0;
        }
        Object store = ReflectionShopSupport.invokeNoArg(shops, "store");
        Collection<Object> listings = ReflectionShopSupport.asCollection(
                ReflectionShopSupport.invokeNoArg(store, "all"));
        if (listings.isEmpty()) {
            return 0;
        }
        List<Object> batch = new ArrayList<>(listings);
        if (shopCursor >= batch.size()) {
            shopCursor = 0;
        }
        int scanned = 0;
        while (scanned < maxShopsPerScan && !batch.isEmpty()) {
            Object listing = batch.get(shopCursor++ % batch.size());
            if (shopCursor >= batch.size()) {
                shopCursor = 0;
            }
            ShopBlockRef blockRef = shopBlockRef(listing);
            if (blockRef != null) {
                shopBlocks.add(blockRef.key());
            }
            GoldBreakdown gold = blockRef == null ? new GoldBreakdown() : goldInShopBlock(blockRef);
            if (gold.totalG() <= 0.0001) {
                scanned++;
                continue;
            }
            String ownerUuid = shopOwnerUuid(listing);
            if (ownerUuid != null && !ownerUuid.isBlank()) {
                MutableRow row = byUuid.computeIfAbsent(
                        ownerUuid.toLowerCase(Locale.ROOT),
                        k -> new MutableRow(ownerUuid, shopOwnerName(listing)));
                if (row.username == null || row.username.isBlank()) {
                    row.username = shopOwnerName(listing);
                }
                row.shop.add(gold);
            } else {
                unattributedOut.add(gold);
            }
            scanned++;
        }
        return scanned;
    }

    private static String shopOwnerUuid(Object listing) {
        Object value = ReflectionShopSupport.invokeNoArg(listing, "ownerUuid");
        return value == null ? null : String.valueOf(value);
    }

    private static String shopOwnerName(Object listing) {
        Object value = ReflectionShopSupport.invokeNoArg(listing, "ownerName");
        return value == null ? null : String.valueOf(value);
    }

    private record ShopBlockRef(String world, int x, int y, int z) {
        String key() {
            return world + ":" + x + ":" + y + ":" + z;
        }
    }

    private static ShopBlockRef shopBlockRef(Object listing) {
        String worldName = stringField(listing, "world");
        if (worldName == null || worldName.isBlank()) {
            return null;
        }
        return new ShopBlockRef(worldName, intField(listing, "x"), intField(listing, "y"), intField(listing, "z"));
    }

    private static GoldBreakdown goldInShopBlock(ShopBlockRef ref) {
        World world = Bukkit.getWorld(ref.world());
        if (world == null) {
            return new GoldBreakdown();
        }
        Chunk chunk = world.getChunkAt(ref.x() >> 4, ref.z() >> 4);
        if (!chunk.isLoaded()) {
            chunk.load();
        }
        Block block = world.getBlockAt(ref.x(), ref.y(), ref.z());
        if (!(block.getState() instanceof Container container)) {
            return new GoldBreakdown();
        }
        return goldInContainerTree(container);
    }

    private static GoldBreakdown goldInContainerTree(Container container) {
        GoldBreakdown total = new GoldBreakdown();
        total.addInventory(container.getInventory());
        Block block = container.getBlock();
        if (block == null) {
            return total;
        }
        Material type = block.getType();
        if (type.name().endsWith("_CHEST") && !type.name().contains("TRAPPED")) {
            try {
                if (block.getState() instanceof org.bukkit.block.Chest chest) {
                    Inventory doubleInv = chest.getInventory();
                    if (doubleInv != null) {
                        GoldBreakdown merged = new GoldBreakdown();
                        merged.addInventory(container.getInventory());
                        merged.addInventory(doubleInv);
                        if (merged.totalG() > total.totalG()) {
                            return merged;
                        }
                    }
                }
            } catch (IllegalStateException ignored) {
                // Stale chunk snapshot — use container inventory only.
            }
        }
        return total;
    }

    private GoldBreakdown scanWorldContainers(Map<String, MutableRow> byUuid, Set<String> shopBlocks) {
        Chunk[] loaded = collectLoadedChunks();
        if (loaded.length == 0) {
            lastChunksScanned = 0;
            lastTownyBlocksScanned = 0;
            return new GoldBreakdown();
        }
        GoldBreakdown unattributed = new GoldBreakdown();
        int scanned = 0;
        while (scanned < maxChunksPerScan) {
            if (chunkCursor >= loaded.length) {
                chunkCursor = 0;
            }
            Chunk chunk = loaded[chunkCursor++];
            try {
                for (BlockState state : chunk.getTileEntities()) {
                    if (!(state instanceof Container container)) {
                        continue;
                    }
                    if (!isStorageContainer(state.getType())) {
                        continue;
                    }
                    World world = state.getWorld();
                    Block block = state.getBlock();
                    if (world != null) {
                        String key = world.getName()
                                + ":"
                                + state.getX()
                                + ":"
                                + state.getY()
                                + ":"
                                + state.getZ();
                        if (shopBlocks.contains(key)) {
                            continue;
                        }
                    }
                    GoldBreakdown found = goldInContainerTree(container);
                    if (found.totalG() <= 0.0001) {
                        continue;
                    }
                    String residentUuid = block == null ? null : TownyGoldHelper.residentUuid(block);
                    if (residentUuid != null && !residentUuid.isBlank()) {
                        MutableRow row = byUuid.computeIfAbsent(
                                residentUuid.toLowerCase(Locale.ROOT),
                                k -> new MutableRow(residentUuid, TownyGoldHelper.residentName(block)));
                        if (row.username == null || row.username.isBlank()) {
                            row.username = TownyGoldHelper.residentName(block);
                        }
                        row.chest.add(found);
                    } else {
                        unattributed.add(found);
                    }
                }
                TownyGoldHelper.scanChunkPlacedGold(chunk, byUuid, unattributed);
                lastTownyBlocksScanned++;
            } catch (IllegalStateException ignored) {
                // Paper may reject tile entities without loaded block data in this chunk slice.
            }
            scanned++;
        }
        lastChunksScanned = scanned;
        return unattributed;
    }

    private static boolean isStorageContainer(Material type) {
        if (type == null) {
            return false;
        }
        String name = type.name();
        return name.endsWith("_CHEST")
                || name.endsWith("_BARREL")
                || type == Material.SHULKER_BOX
                || name.endsWith("_SHULKER_BOX");
    }

    private static Chunk[] collectLoadedChunks() {
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

    private static String stringField(Object target, String method) {
        Object value = ReflectionShopSupport.invokeNoArg(target, method);
        return value == null ? null : String.valueOf(value);
    }

    private static int intField(Object target, String method) {
        Object value = ReflectionShopSupport.invokeNoArg(target, method);
        return value instanceof Number number ? number.intValue() : 0;
    }

    public static final class MutableRow {
        final String uuid;
        String username;
        final GoldBreakdown inventory = new GoldBreakdown();
        final GoldBreakdown ender = new GoldBreakdown();
        final GoldBreakdown shop = new GoldBreakdown();
        final GoldBreakdown chest = new GoldBreakdown();
        final GoldBreakdown towny = new GoldBreakdown();
        double inventoryG;
        double enderG;
        double shopG;
        double chestG;
        double townyPlacedG;
        double totalG;

        MutableRow(String uuid, String username) {
            this.uuid = uuid;
            this.username = username;
        }

        void recomputeTotals() {
            inventoryG = inventory.totalG();
            enderG = ender.totalG();
            shopG = shop.totalG();
            chestG = chest.totalG();
            townyPlacedG = towny.totalG();
            totalG = inventoryG + enderG + shopG + chestG + townyPlacedG;
        }

        PlayerRow freeze() {
            return new PlayerRow(
                    uuid,
                    username,
                    inventoryG,
                    enderG,
                    shopG,
                    chestG,
                    townyPlacedG,
                    totalG,
                    copy(inventory),
                    copy(ender),
                    copy(chest),
                    copy(shop),
                    copy(towny));
        }

        private static GoldBreakdown copy(GoldBreakdown source) {
            return new GoldBreakdown(source.nuggetG, source.ingotG, source.blockG, source.otherG);
        }
    }
}
