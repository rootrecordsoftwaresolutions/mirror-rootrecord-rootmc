package com.rootrecord.minecraft.rootstat.economy;

import com.rootrecord.minecraft.common.RootMcEconomyBridge;
import com.rootrecord.minecraft.rootstat.RootStatBridge;
import com.rootrecord.minecraft.rootstat.economy.shop.ShopItemKeys;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Market-value summary for items a player is carrying (shop average prices). */
public final class MarketValueReport {

    private static final int MAX_BREAKDOWN_LINES = 14;

    private MarketValueReport() {}

    public record Line(String itemKey, int quantity, double unitPrice, double lineTotal) {}

    public static void sendCarriedValue(Player player, RootStatBridge bridge) {
        RootMcEconomyBridge economy = economyBridge(bridge);
        if (economy == null) {
            player.sendMessage(bridge.colorize(bridge.msg("config-missing")));
            return;
        }

        List<Line> lines = collectLines(player, economy);
        ItemStack hand = player.getInventory().getItemInMainHand();
        sendHandLine(player, bridge, economy, hand);

        if (lines.isEmpty()) {
            player.sendMessage(bridge.colorize(bridge.msg("value-carry-empty")));
            return;
        }

        double total = lines.stream().mapToDouble(Line::lineTotal).sum();
        int stackCount = lines.stream().mapToInt(Line::quantity).sum();
        player.sendMessage(bridge.colorize(
                bridge.msg("value-carry-total")
                        .replace("{total}", formatGold(total))
                        .replace("{stacks}", String.valueOf(stackCount))
                        .replace("{items}", String.valueOf(lines.size()))));

        int shown = 0;
        double shownTotal = 0;
        for (Line line : lines) {
            if (shown >= MAX_BREAKDOWN_LINES) {
                break;
            }
            player.sendMessage(bridge.colorize(
                    bridge.msg("value-carry-line")
                            .replace("{item}", prettyName(line.itemKey()))
                            .replace("{qty}", String.valueOf(line.quantity()))
                            .replace("{each}", formatGold(line.unitPrice()))
                            .replace("{total}", formatGold(line.lineTotal()))));
            shownTotal += line.lineTotal();
            shown++;
        }
        if (lines.size() > shown) {
            double rest = total - shownTotal;
            player.sendMessage(bridge.colorize(
                    bridge.msg("value-carry-more")
                            .replace("{count}", String.valueOf(lines.size() - shown))
                            .replace("{total}", formatGold(rest))));
        }
    }

    private static void sendHandLine(Player player, RootStatBridge bridge, RootMcEconomyBridge economy, ItemStack hand) {
        if (hand == null || hand.getType().isAir()) {
            player.sendMessage(bridge.colorize(bridge.msg("value-hand-none")));
            return;
        }
        String itemKey = ShopItemKeys.fromItemStack(hand);
        double each = itemKey == null ? 0 : economy.averagePrice(itemKey);
        if (each <= 0) {
            player.sendMessage(bridge.colorize(
                    bridge.msg("value-not-found").replace("{item}", prettyName(itemKey != null ? itemKey : hand.getType().name()))));
            return;
        }
        int qty = Math.max(1, hand.getAmount());
        int stackSize = hand.getMaxStackSize();
        double stackValue = each * stackSize;
        player.sendMessage(bridge.colorize(
                bridge.msg("value-hand")
                        .replace("{item}", prettyName(itemKey))
                        .replace("{qty}", String.valueOf(qty))
                        .replace("{each}", formatGold(each))
                        .replace("{stack}", formatGold(stackValue))
                        .replace("{size}", String.valueOf(stackSize))));
    }

    private static List<Line> collectLines(Player player, RootMcEconomyBridge economy) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        PlayerInventory inv = player.getInventory();
        accumulate(counts, inv.getItemInMainHand());
        accumulate(counts, inv.getItemInOffHand());
        for (ItemStack stack : inv.getStorageContents()) {
            accumulate(counts, stack);
        }
        for (ItemStack stack : inv.getArmorContents()) {
            accumulate(counts, stack);
        }

        List<Line> lines = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            double unit = economy.averagePrice(entry.getKey());
            if (unit <= 0) {
                continue;
            }
            int qty = entry.getValue();
            lines.add(new Line(entry.getKey(), qty, unit, unit * qty));
        }
        lines.sort(Comparator.comparingDouble(Line::lineTotal).reversed());
        return lines;
    }

    private static void accumulate(Map<String, Integer> counts, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        String key = ShopItemKeys.fromItemStack(stack);
        if (key == null) {
            return;
        }
        counts.merge(key, stack.getAmount(), Integer::sum);
    }

    private static RootMcEconomyBridge economyBridge(RootStatBridge bridge) {
        if (bridge.getPlugin() instanceof RootMcEconomyBridge eco) {
            return eco;
        }
        return null;
    }

    public static String formatGold(double value) {
        if (value >= 1_000_000) {
            return String.format(Locale.ROOT, "%.3fM G", value / 1_000_000d);
        }
        if (value >= 1_000) {
            return String.format(Locale.ROOT, "%.1f G", value);
        }
        if (value >= 10) {
            return String.format(Locale.ROOT, "%.1f G", value);
        }
        return String.format(Locale.ROOT, "%.3f G", value);
    }

    private static String prettyName(String itemKey) {
        return itemKey.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
