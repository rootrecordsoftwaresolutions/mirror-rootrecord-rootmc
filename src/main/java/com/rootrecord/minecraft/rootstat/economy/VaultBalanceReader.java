package com.rootrecord.minecraft.rootstat.economy;


import com.rootrecord.minecraft.common.GoldMoney;

import com.rootrecord.minecraft.common.RootMcEconomyService;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** Reads balances and transfers via Vault Economy or Root Essentials MySQL export. */
public final class VaultBalanceReader {

    private final Logger logger;
    private Economy vault;
    private RootMcEconomyService rootEconomy;

    public VaultBalanceReader(Logger logger) {
        this.logger = logger;
        hook();
    }

    public boolean isAvailable() {
        return vault != null || rootEconomy != null || essentialsBalanceExportAvailable();
    }

    public boolean usesVault() {
        return vault != null;
    }

    public void hook() {
        vault = null;
        rootEconomy = null;

        if (Bukkit.getServer().getPluginManager().getPlugin("Vault") != null) {
            var registration = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (registration != null) {
                vault = registration.getProvider();
                return;
            }
        }

        var rootRsp = Bukkit.getServicesManager().getRegistration(RootMcEconomyService.class);
        if (rootRsp != null) {
            rootEconomy = rootRsp.getProvider();
            return;
        }

        if (Bukkit.getServer().getPluginManager().getPlugin("Vault") != null) {
            logger.fine("Vault jar present but no economy provider (Root Essentials not loaded?).");
        }
    }

    public List<EconomySnapshot.BalanceRow> readAllBalances() {
        List<EconomySnapshot.BalanceRow> fromMysql = readEssentialsMysqlBalances();
        if (!fromMysql.isEmpty()) {
            logger.fine("Economy balances from Root Essentials MySQL: " + fromMysql.size());
            return fromMysql;
        }
        if (vault != null) {
            return readVaultBalances();
        }
        if (rootEconomy != null) {
            return readRootBalances();
        }
        return List.of();
    }

    public List<EconomySnapshot.SystemBalanceRow> readSystemBalances() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Root-Essentials");
        if (plugin == null || !plugin.isEnabled()) {
            return List.of();
        }
        try {
            Method method = plugin.getClass().getMethod("allSystemBalancesForSync");
            Object raw = method.invoke(plugin);
            if (!(raw instanceof List<?> list) || list.isEmpty()) {
                return List.of();
            }
            List<EconomySnapshot.SystemBalanceRow> out = new ArrayList<>(list.size());
            for (Object row : list) {
                if (row == null) {
                    continue;
                }
                String uuid = String.valueOf(invoke(row, "minecraftUuid"));
                String username = String.valueOf(invoke(row, "minecraftUsername"));
                double balance = ((Number) invoke(row, "balance")).doubleValue();
                String accountType = String.valueOf(invoke(row, "accountType"));
                if (!Double.isFinite(balance) || balance <= 0) {
                    continue;
                }
                out.add(new EconomySnapshot.SystemBalanceRow(uuid, username, balance, accountType, "G"));
            }
            return out;
        } catch (ReflectiveOperationException ex) {
            logger.fine("Root Essentials system balance export unavailable: " + ex.getMessage());
            return List.of();
        }
    }

    private boolean essentialsBalanceExportAvailable() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Root-Essentials");
        if (plugin == null || !plugin.isEnabled()) {
            return false;
        }
        try {
            plugin.getClass().getMethod("allPlayerBalancesForSync");
            return true;
        } catch (NoSuchMethodException ex) {
            return false;
        }
    }

    private List<EconomySnapshot.BalanceRow> readEssentialsMysqlBalances() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Root-Essentials");
        if (plugin == null || !plugin.isEnabled()) {
            return List.of();
        }
        try {
            Method method = plugin.getClass().getMethod("allPlayerBalancesForSync");
            Object raw = method.invoke(plugin);
            if (!(raw instanceof List<?> list) || list.isEmpty()) {
                return List.of();
            }
            List<EconomySnapshot.BalanceRow> out = new ArrayList<>(list.size());
            for (Object row : list) {
                if (row == null) {
                    continue;
                }
                String uuid = String.valueOf(invoke(row, "minecraftUuid"));
                String username = String.valueOf(invoke(row, "minecraftUsername"));
                double balance = ((Number) invoke(row, "balance")).doubleValue();
                if (!Double.isFinite(balance) || balance <= 0) {
                    continue;
                }
                out.add(new EconomySnapshot.BalanceRow(uuid, username, balance, "G"));
            }
            return out;
        } catch (ReflectiveOperationException ex) {
            logger.fine("Root Essentials MySQL balance export unavailable: " + ex.getMessage());
            return List.of();
        }
    }

    private static Object invoke(Object target, String accessor) throws ReflectiveOperationException {
        return target.getClass().getMethod(accessor).invoke(target);
    }

    private List<EconomySnapshot.BalanceRow> readVaultBalances() {
        List<EconomySnapshot.BalanceRow> out = new ArrayList<>();
        String currency = vault.currencyNamePlural();
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            if (player.getUniqueId() == null) {
                continue;
            }
            double balance = vault.getBalance(player);
            if (!Double.isFinite(balance) || balance <= 0) {
                continue;
            }
            out.add(new EconomySnapshot.BalanceRow(
                    player.getUniqueId().toString(),
                    player.getName(),
                    balance,
                    currency == null || currency.isBlank() ? "default" : currency));
        }
        return out;
    }

    private List<EconomySnapshot.BalanceRow> readRootBalances() {
        List<EconomySnapshot.BalanceRow> out = new ArrayList<>();
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            if (player.getUniqueId() == null) {
                continue;
            }
            double balance = rootEconomy.balance(player.getUniqueId());
            if (!Double.isFinite(balance) || balance <= 0) {
                continue;
            }
            out.add(new EconomySnapshot.BalanceRow(
                    player.getUniqueId().toString(),
                    player.getName(),
                    balance,
                    "G"));
        }
        return out;
    }

    /**
     * Transfer for Discord-queued payments. Returns false if withdraw/deposit failed.
     */
    public boolean transfer(java.util.UUID from, java.util.UUID to, double amount) {
        if (from == null || to == null) {
            return false;
        }
        double amt = GoldMoney.round(amount);
        if (!Double.isFinite(amt) || amt < 0.01d) {
            return false;
        }
        if (vault != null) {
            return transferVault(from, to, amt);
        }
        if (rootEconomy != null) {
            return transferRoot(from, to, amt);
        }
        return false;
    }

    private boolean transferVault(java.util.UUID from, java.util.UUID to, double amt) {
        OfflinePlayer fromPlayer = Bukkit.getOfflinePlayer(from);
        OfflinePlayer toPlayer = Bukkit.getOfflinePlayer(to);
        if (!vault.hasAccount(fromPlayer) || !vault.hasAccount(toPlayer)) {
            return false;
        }
        if (vault.getBalance(fromPlayer) + 0.0001d < amt) {
            return false;
        }
        var response = vault.withdrawPlayer(fromPlayer, amt);
        if (response == null || !response.transactionSuccess()) {
            return false;
        }
        var deposit = vault.depositPlayer(toPlayer, amt);
        if (deposit == null || !deposit.transactionSuccess()) {
            vault.depositPlayer(fromPlayer, amt);
            return false;
        }
        return true;
    }

    private boolean transferRoot(java.util.UUID from, java.util.UUID to, double amt) {
        if (!rootEconomy.has(from, amt)) {
            return false;
        }
        if (!rootEconomy.withdraw(from, amt)) {
            return false;
        }
        try {
            rootEconomy.deposit(to, amt);
            return true;
        } catch (RuntimeException ex) {
            rootEconomy.deposit(from, amt);
            return false;
        }
    }
}
