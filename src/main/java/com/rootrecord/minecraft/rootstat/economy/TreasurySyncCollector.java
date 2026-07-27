package com.rootrecord.minecraft.rootstat.economy;

import com.rootrecord.minecraft.common.RootMcTreasuryResolver;
import com.rootrecord.minecraft.common.RootMcTreasuryService;
import com.rootrecord.minecraft.rootstat.RootStatBridge;
import com.rootrecord.minecraft.rootstat.config.RootStatConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Optional Towny SQL + treasury rows for cloud economy sync. */
public final class TreasurySyncCollector {

    private final RootStatBridge bridge;
    private volatile long lastLedgerMysqlId;

    public TreasurySyncCollector(RootStatBridge bridge) {
        this.bridge = bridge;
    }

    public void setLastLedgerMysqlId(long id) {
        this.lastLedgerMysqlId = Math.max(0, id);
    }

    public long lastLedgerMysqlId() {
        return lastLedgerMysqlId;
    }

    public List<EconomySnapshot.TreasuryLedgerRow> collectLedgerRows() {
        RootMcTreasuryService treasury = RootMcTreasuryResolver.resolve(bridge.getPlugin());
        if (treasury == null) {
            return List.of();
        }
        List<EconomySnapshot.TreasuryLedgerRow> out = new ArrayList<>();
        for (RootMcTreasuryService.TreasuryLedgerEntry entry : treasury.ledgerEntriesAfter(lastLedgerMysqlId, 400)) {
            out.add(new EconomySnapshot.TreasuryLedgerRow(
                    entry.mysqlId(),
                    entry.type().name(),
                    entry.amount(),
                    entry.fromUuid() == null ? null : entry.fromUuid().toString(),
                    entry.toUuid() == null ? null : entry.toUuid().toString(),
                    entry.details(),
                    entry.createdAt()));
            if (entry.mysqlId() > lastLedgerMysqlId) {
                lastLedgerMysqlId = entry.mysqlId();
            }
        }
        return out;
    }

    public double treasuryBalance() {
        RootMcTreasuryService treasury = RootMcTreasuryResolver.resolve(bridge.getPlugin());
        return treasury == null ? 0 : treasury.headlineReserveBalance();
    }

    public List<EconomySnapshot.PlaytimeMonthlyRow> collectMonthlyPlaytime() {
        if (bridge.playtime() == null) {
            return List.of();
        }
        try {
            List<EconomySnapshot.PlaytimeMonthlyRow> out = new ArrayList<>();
            for (var row : bridge.playtime().readCurrentAndPriorMonths()) {
                out.add(new EconomySnapshot.PlaytimeMonthlyRow(row.uuid(), row.monthKey(), row.playtimeSeconds()));
            }
            return out;
        } catch (SQLException ex) {
            bridge.getPlugin().getLogger().warning("Monthly playtime sync read failed: " + ex.getMessage());
            return List.of();
        }
    }

    public List<EconomySnapshot.TownTaxRow> collectTownTaxRates() {
        RootStatConfig config = bridge.config();
        if (!config.isMysqlEnabled()) {
            return List.of();
        }
        try (Connection c = java.sql.DriverManager.getConnection(
                config.jdbcUrl(), config.mysqlUsername(), config.mysqlPassword());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name, mayor, taxpercent FROM towny_towns ORDER BY name ASC LIMIT 200");
             ResultSet rs = ps.executeQuery()) {
            List<EconomySnapshot.TownTaxRow> out = new ArrayList<>();
            while (rs.next()) {
                String town = rs.getString("name");
                if (town == null || town.isBlank()) {
                    continue;
                }
                out.add(new EconomySnapshot.TownTaxRow(town, rs.getString("mayor"), rs.getDouble("taxpercent")));
            }
            return out;
        } catch (SQLException ex) {
            bridge.getPlugin().getLogger().fine("Towny tax sync skipped: " + ex.getMessage());
            return List.of();
        }
    }
}
