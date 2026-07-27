package com.rootrecord.minecraft.rootmc.treasury;

import com.rootrecord.minecraft.common.DeathFeeSettlement;
import com.rootrecord.minecraft.common.RootMcEconomyResolver;
import com.rootrecord.minecraft.common.RootMcNewPlayerGrace;
import com.rootrecord.minecraft.common.RootMcTreasuryResolver;
import com.rootrecord.minecraft.common.ShadedServiceBridge;
import com.rootrecord.minecraft.rootmc.RootMcPlugin;
import com.rootrecord.minecraft.common.GoldMoney;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/** Death fee: partial recycle to treasury (DEATH ledger); PvP remainder to killer (OTHER audit row). */
public final class DeathTreasuryListener implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final RootMcPlugin plugin;
    private volatile boolean loggedTreasuryMissing;

    public DeathTreasuryListener(RootMcPlugin plugin) {
        this.plugin = plugin;
        logStartupState();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        DeathConfig cfg = DeathConfig.read(plugin);
        if (!cfg.enabled) {
            return;
        }
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (cfg.pvpOnly && killer == null) {
            return;
        }
        if (killer != null && killer.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        RootMcNewPlayerGrace grace = ShadedServiceBridge.resolveNewPlayerGrace(plugin);
        if (grace != null && grace.exemptFromDeathTax(victim.getUniqueId())) {
            return;
        }
        var treasury = RootMcTreasuryResolver.resolve(plugin);
        if (treasury == null) {
            if (!loggedTreasuryMissing) {
                loggedTreasuryMissing = true;
                plugin.getLogger().warning(
                        "Death fee skipped — Root-Essentials treasury bridge unavailable "
                                + "(need root-essentials 1.4.69+ with MySQL economy).");
            }
            return;
        }
        DeathFeeSettlement settlement = treasury.settleDeathFee(
                victim.getUniqueId(),
                victim.getName(),
                killer == null ? null : killer.getUniqueId(),
                killer == null ? null : killer.getName(),
                cfg.victimBalancePercent,
                cfg.treasuryShareOfFee,
                cfg.minFeeGold);
        if (settlement == null || settlement.grossFee() < GoldMoney.MIN_AMOUNT) {
            return;
        }
        if (cfg.notifyGlobal) {
            appendGlobalDeathFee(event, victim, killer, settlement);
            return;
        }
        if (cfg.notifyVictim) {
            victim.sendMessage(colorize(buildVictimFeeMessage(settlement, killer)));
        }
        if (cfg.notifyKiller && killer != null && settlement.killerAmount() >= GoldMoney.MIN_AMOUNT) {
            killer.sendMessage(colorize(msg("death-fee-killer")
                    .replace("{amount}", money(settlement.killerAmount()))
                    .replace("{treasury}", money(settlement.treasuryAmount()))
                    .replace("{victim}", victim.getName())
                    .replace("{currency}", currencyLabel())));
        }
    }

    private void appendGlobalDeathFee(
            PlayerDeathEvent event,
            Player victim,
            Player killer,
            DeathFeeSettlement settlement) {
        Component feeSuffix = LEGACY.deserialize(colorize(buildGlobalFeeMessage(settlement, killer)));
        Component death = event.deathMessage();
        if (death != null) {
            event.deathMessage(death.append(Component.space()).append(feeSuffix));
            return;
        }
        Component fallback = Component.text(victim.getName() + " died").append(Component.space()).append(feeSuffix);
        event.deathMessage(fallback);
    }

    private String buildVictimFeeMessage(DeathFeeSettlement settlement, Player killer) {
        String victimMsg = msg("death-fee-victim")
                .replace("{fee}", money(settlement.grossFee()))
                .replace("{treasury}", money(settlement.treasuryAmount()))
                .replace("{currency}", currencyLabel());
        if (killer != null && settlement.killerAmount() >= GoldMoney.MIN_AMOUNT) {
            victimMsg += msg("death-fee-victim-pvp-suffix")
                    .replace("{killer_amount}", money(settlement.killerAmount()))
                    .replace("{killer}", killer.getName())
                    .replace("{currency}", currencyLabel());
        }
        return victimMsg;
    }

    private String buildGlobalFeeMessage(DeathFeeSettlement settlement, Player killer) {
        String pvpSuffix = "";
        if (killer != null && settlement.killerAmount() >= GoldMoney.MIN_AMOUNT) {
            pvpSuffix = msg("death-fee-global-pvp-suffix")
                    .replace("{killer_amount}", money(settlement.killerAmount()))
                    .replace("{killer}", killer.getName())
                    .replace("{currency}", currencyLabel());
        }
        return msg("death-fee-global")
                .replace("{fee}", money(settlement.grossFee()))
                .replace("{treasury}", money(settlement.treasuryAmount()))
                .replace("{currency}", currencyLabel())
                .replace("{pvp_suffix}", pvpSuffix);
    }

    private void logStartupState() {
        DeathConfig cfg = DeathConfig.read(plugin);
        if (!cfg.enabled) {
            plugin.getLogger().info("Death fee disabled in plugins/RootMC/rootmc.yml (treasury.death.enabled).");
            return;
        }
        plugin.getLogger().info(String.format(
                java.util.Locale.US,
                "Death fee enabled — %.0f%% of balance (min %.3f G), pvp-only=%s, reserve share=%.0f%%.",
                cfg.victimBalancePercent * 100.0,
                cfg.minFeeGold,
                cfg.pvpOnly,
                cfg.treasuryShareOfFee * 100.0));
    }

    private record DeathConfig(
            boolean enabled,
            boolean pvpOnly,
            double victimBalancePercent,
            double treasuryShareOfFee,
            double minFeeGold,
            boolean notifyGlobal,
            boolean notifyVictim,
            boolean notifyKiller) {

        static DeathConfig read(RootMcPlugin plugin) {
            ConfigurationSection cfg = plugin.rootMcYaml().getConfigurationSection("treasury.death");
            boolean notifyGlobal = cfg == null || cfg.getBoolean("notify-global", true);
            return new DeathConfig(
                    cfg != null && cfg.getBoolean("enabled", false),
                    cfg != null && cfg.getBoolean("pvp-only", false),
                    cfg == null ? 0.15 : Math.max(0, cfg.getDouble("victim-balance-percent", 0.15)),
                    cfg == null ? 0.40 : clamp(cfg.getDouble("treasury-share-of-fee", 0.40), 0, 1),
                    cfg == null ? 1.0 : Math.max(0, cfg.getDouble("min-fee-g", 1.0)),
                    notifyGlobal,
                    !notifyGlobal && (cfg == null || cfg.getBoolean("notify-victim", true)),
                    !notifyGlobal && (cfg == null || cfg.getBoolean("notify-killer", true)));
        }
    }

    private String msg(String key) {
        return plugin.rawMsg(key);
    }

    private String currencyLabel() {
        return RootMcEconomyResolver.resolve(plugin) == null ? "G" : "G";
    }

    private String money(double amount) {
        return GoldMoney.format(amount);
    }

    private static String colorize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
