package com.rootrecord.minecraft.rootstat.sync;

import com.rootrecord.minecraft.common.GrowthScale;
import com.rootrecord.minecraft.common.McDayClock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.EnumSet;
import java.util.Set;

/** Scales bone meal, bee pollination, and villager crop farming to the MC-day length. */
public final class McDayExtraGrowthListener implements Listener {

    private static final Set<Material> FARM_CROPS = EnumSet.of(
            Material.WHEAT,
            Material.CARROTS,
            Material.POTATOES,
            Material.BEETROOTS,
            Material.NETHER_WART,
            Material.COCOA,
            Material.SWEET_BERRY_BUSH,
            Material.CAVE_VINES,
            Material.CAVE_VINES_PLANT,
            Material.TORCHFLOWER,
            Material.PITCHER_CROP);

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBoneMeal(BlockFertilizeEvent event) {
        if (!shouldScale()) {
            return;
        }
        double scale = McDayClock.growthScale();

        // Saplings / trees: leave BlockFertilizeEvent alone so vanilla can grow the tree.
        // StructureGrowEvent applies one pass/fail for the whole tree.
        if (Tag.SAPLINGS.isTagged(event.getBlock().getType()) || containsTreeStructure(event)) {
            return;
        }

        event.setCancelled(true);
        boolean allowDecorative = GrowthScale.passes(scale);
        for (var newState : event.getBlocks()) {
            Block block = newState.getBlock();
            BlockData current = block.getBlockData();
            BlockData planned = newState.getBlockData();
            if (current instanceof Ageable currentAge && planned instanceof Ageable plannedAge) {
                int delta = plannedAge.getAge() - currentAge.getAge();
                int scaled = GrowthScale.scaledDelta(delta, scale);
                if (scaled <= 0) {
                    continue;
                }
                Ageable applied = (Ageable) currentAge.clone();
                applied.setAge(Math.min(currentAge.getAge() + scaled, currentAge.getMaximumAge()));
                block.setBlockData(applied);
            } else if (allowDecorative) {
                newState.update(true, false);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBoneMealTree(StructureGrowEvent event) {
        if (!shouldScale() || event.getPlayer() == null) {
            return;
        }
        if (!GrowthScale.passes(McDayClock.growthScale())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBeePollination(BlockGrowEvent event) {
        if (!shouldScale()) {
            return;
        }
        BlockData current = event.getBlock().getBlockData();
        BlockData planned = event.getNewState().getBlockData();
        if (!(current instanceof Ageable currentAge) || !(planned instanceof Ageable plannedAge)) {
            return;
        }
        if (plannedAge.getAge() <= currentAge.getAge()) {
            return;
        }
        if (!hasPollinatingBeeNearby(event.getBlock())) {
            return;
        }
        if (!GrowthScale.passes(McDayClock.growthScale())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVillagerFarm(EntityChangeBlockEvent event) {
        if (!shouldScale() || !(event.getEntity() instanceof Villager villager)) {
            return;
        }
        if (villager.getProfession() != Villager.Profession.FARMER) {
            return;
        }
        if (!FARM_CROPS.contains(event.getBlock().getType())
                && !FARM_CROPS.contains(event.getTo())) {
            return;
        }
        if (!GrowthScale.passes(McDayClock.growthScale())) {
            event.setCancelled(true);
        }
    }

    private static boolean containsTreeStructure(BlockFertilizeEvent event) {
        for (var state : event.getBlocks()) {
            Material type = state.getType();
            if (Tag.LOGS.isTagged(type) || Tag.LEAVES.isTagged(type)) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldScale() {
        return McDayClock.enabled() && McDayClock.growthScale() > 1.0;
    }

    private static boolean hasPollinatingBeeNearby(Block block) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        double radius = 2.5;
        for (Entity entity : block.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof Bee bee)) {
                continue;
            }
            if (bee.hasNectar() || bee.getCropsGrownSincePollination() > 0) {
                return true;
            }
        }
        return false;
    }
}
