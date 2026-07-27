package com.rootrecord.minecraft.rootstat.mysql;

import com.rootrecord.minecraft.rootmc.metrics.HostMetricsSample;
import com.rootrecord.minecraft.rootstat.config.RootStatConfig;
import com.rootrecord.minecraft.rootstat.economy.GoldBreakdown;
import com.rootrecord.minecraft.rootstat.economy.PhysicalGoldStorageScanner;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** MySQL reporting read model consumed by rootmc-api through Hyperdrive. */
public final class ReportingStore {

    private final String prefix;
    private final Supplier<Connection> connections;

    public ReportingStore(RootStatConfig config, Supplier<Connection> connections) {
        this.prefix = config.mysqlTablePrefix();
        this.connections = connections;
    }

    public void initSchema() throws SQLException {
        try (Connection connection = connections.get(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %sitem_census_summary (
                      server_id VARCHAR(64) PRIMARY KEY,
                      scanned_at BIGINT NOT NULL,
                      scan_note VARCHAR(512) NULL,
                      distinct_items INT NOT NULL,
                      gold_mint_peg_g DOUBLE NOT NULL DEFAULT 0,
                      updated_at DATETIME(3) NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(prefix));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %sitem_census_rows (
                      server_id VARCHAR(64) NOT NULL,
                      item_id VARCHAR(128) NOT NULL,
                      item_count BIGINT NOT NULL,
                      avg_g DOUBLE NULL,
                      mint_peg_g DOUBLE NULL,
                      updated_at DATETIME(3) NOT NULL,
                      PRIMARY KEY (server_id, item_id),
                      INDEX idx_item_census_count (server_id, item_count)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(prefix));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %sitem_census_daily_summary (
                      server_id VARCHAR(64) NOT NULL,
                      snapshot_date DATE NOT NULL,
                      scanned_at BIGINT NOT NULL,
                      scan_note VARCHAR(512) NULL,
                      distinct_items INT NOT NULL,
                      gold_mint_peg_g DOUBLE NOT NULL DEFAULT 0,
                      updated_at DATETIME(3) NOT NULL,
                      PRIMARY KEY (server_id, snapshot_date)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(prefix));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %sitem_census_daily_rows (
                      server_id VARCHAR(64) NOT NULL,
                      snapshot_date DATE NOT NULL,
                      item_id VARCHAR(128) NOT NULL,
                      item_count BIGINT NOT NULL,
                      avg_g DOUBLE NULL,
                      mint_peg_g DOUBLE NULL,
                      scanned_at BIGINT NOT NULL,
                      PRIMARY KEY (server_id, snapshot_date, item_id),
                      INDEX idx_item_census_daily (server_id, snapshot_date)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(prefix));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %sphysical_gold_summary (
                      server_id VARCHAR(64) PRIMARY KEY,
                      total_storage_g DOUBLE NOT NULL,
                      unattributed_g DOUBLE NOT NULL,
                      player_count INT NOT NULL,
                      shops_scanned INT NOT NULL,
                      chunks_scanned INT NOT NULL,
                      towny_blocks_scanned INT NOT NULL,
                      scanned_at DATETIME(3) NOT NULL,
                      updated_at DATETIME(3) NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(prefix));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %splayer_physical_gold (
                      server_id VARCHAR(64) NOT NULL,
                      minecraft_uuid CHAR(36) NOT NULL,
                      minecraft_username VARCHAR(16) NULL,
                      inventory_g DOUBLE NOT NULL,
                      ender_g DOUBLE NOT NULL,
                      shop_g DOUBLE NOT NULL,
                      chest_g DOUBLE NOT NULL,
                      towny_placed_g DOUBLE NOT NULL,
                      total_g DOUBLE NOT NULL,
                      scanned_at DATETIME(3) NOT NULL,
                      PRIMARY KEY (server_id, minecraft_uuid),
                      INDEX idx_physical_gold_total (server_id, total_g)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(prefix));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %shost_metrics_minute (
                      host_key VARCHAR(64) NOT NULL,
                      minute_ts DATETIME NOT NULL,
                      cpu_avg_pct DOUBLE NOT NULL,
                      ram_avg_pct DOUBLE NOT NULL,
                      disk_used_pct DOUBLE NOT NULL,
                      tps_avg DOUBLE NULL,
                      sample_count INT NOT NULL,
                      updated_at DATETIME(3) NOT NULL,
                      PRIMARY KEY (host_key, minute_ts),
                      INDEX idx_host_metrics_minute (minute_ts)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(prefix));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %shost_metrics_day (
                      host_key VARCHAR(64) NOT NULL,
                      metric_date DATE NOT NULL,
                      cpu_sum DOUBLE NOT NULL,
                      ram_sum DOUBLE NOT NULL,
                      disk_sum DOUBLE NOT NULL,
                      tps_sum DOUBLE NOT NULL,
                      sample_total INT NOT NULL,
                      minute_count INT NOT NULL,
                      updated_at DATETIME(3) NOT NULL,
                      PRIMARY KEY (host_key, metric_date)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(prefix));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %sserver_status (
                      server_id VARCHAR(64) PRIMARY KEY,
                      online_players INT NOT NULL,
                      plugin_version VARCHAR(32) NOT NULL,
                      game_version VARCHAR(32) NOT NULL,
                      updated_at DATETIME(3) NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(prefix));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %stowny_towns_snapshot (
                      server_id VARCHAR(64) NOT NULL,
                      town_uuid CHAR(36) NOT NULL,
                      town_name VARCHAR(64) NOT NULL,
                      mayor_uuid CHAR(36) NULL,
                      mayor_name VARCHAR(16) NULL,
                      resident_count INT NOT NULL,
                      nation_name VARCHAR(64) NULL,
                      is_capital BOOLEAN NOT NULL,
                      plot_count INT NOT NULL,
                      town_balance_gold DOUBLE NOT NULL,
                      updated_at DATETIME(3) NOT NULL,
                      PRIMARY KEY (server_id, town_uuid)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(prefix));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %stowny_nations_snapshot (
                      server_id VARCHAR(64) NOT NULL,
                      nation_uuid CHAR(36) NOT NULL,
                      nation_name VARCHAR(64) NOT NULL,
                      leader_uuid CHAR(36) NULL,
                      leader_name VARCHAR(16) NULL,
                      town_count INT NOT NULL,
                      updated_at DATETIME(3) NOT NULL,
                      PRIMARY KEY (server_id, nation_uuid)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(prefix));
        }
    }

    public void replaceItemCensus(
            String serverId,
            long scannedAt,
            String scanNote,
            Map<String, Long> counts,
            Map<String, Double> averages,
            Map<String, Double> mintPegTotals,
            double goldMintPegG)
            throws SQLException {
        Timestamp now = Timestamp.from(Instant.now());
        LocalDate snapshotDate = Instant.ofEpochMilli(scannedAt)
                .atZone(ZoneId.of("Pacific/Honolulu"))
                .toLocalDate();
        try (Connection connection = connections.get()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM " + prefix + "item_census_rows WHERE server_id = ?")) {
                    delete.setString(1, serverId);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO %sitem_census_rows
                          (server_id, item_id, item_count, avg_g, mint_peg_g, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """.formatted(prefix))) {
                    for (Map.Entry<String, Long> entry : counts.entrySet()) {
                        if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                            continue;
                        }
                        insert.setString(1, serverId);
                        insert.setString(2, entry.getKey());
                        insert.setLong(3, entry.getValue());
                        setNullableDouble(insert, 4, averages.get(entry.getKey()));
                        setNullableDouble(insert, 5, mintPegTotals.get(entry.getKey()));
                        insert.setTimestamp(6, now);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                try (PreparedStatement deleteDaily = connection.prepareStatement(
                                "DELETE FROM " + prefix
                                        + "item_census_daily_rows WHERE server_id = ? AND snapshot_date = ?");
                        PreparedStatement insertDaily = connection.prepareStatement("""
                                INSERT INTO %sitem_census_daily_rows
                                  (server_id, snapshot_date, item_id, item_count, avg_g, mint_peg_g, scanned_at)
                                VALUES (?, ?, ?, ?, ?, ?, ?)
                                """.formatted(prefix))) {
                    deleteDaily.setString(1, serverId);
                    deleteDaily.setObject(2, snapshotDate);
                    deleteDaily.executeUpdate();
                    for (Map.Entry<String, Long> entry : counts.entrySet()) {
                        if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                            continue;
                        }
                        insertDaily.setString(1, serverId);
                        insertDaily.setObject(2, snapshotDate);
                        insertDaily.setString(3, entry.getKey());
                        insertDaily.setLong(4, entry.getValue());
                        setNullableDouble(insertDaily, 5, averages.get(entry.getKey()));
                        setNullableDouble(insertDaily, 6, mintPegTotals.get(entry.getKey()));
                        insertDaily.setLong(7, scannedAt);
                        insertDaily.addBatch();
                    }
                    insertDaily.executeBatch();
                }
                try (PreparedStatement summary = connection.prepareStatement("""
                        INSERT INTO %sitem_census_summary
                          (server_id, scanned_at, scan_note, distinct_items, gold_mint_peg_g, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          scanned_at = VALUES(scanned_at),
                          scan_note = VALUES(scan_note),
                          distinct_items = VALUES(distinct_items),
                          gold_mint_peg_g = VALUES(gold_mint_peg_g),
                          updated_at = VALUES(updated_at)
                        """.formatted(prefix))) {
                    summary.setString(1, serverId);
                    summary.setLong(2, scannedAt);
                    summary.setString(3, scanNote);
                    summary.setInt(4, counts.size());
                    summary.setDouble(5, goldMintPegG);
                    summary.setTimestamp(6, now);
                    summary.executeUpdate();
                }
                try (PreparedStatement dailySummary = connection.prepareStatement("""
                        INSERT INTO %sitem_census_daily_summary
                          (server_id, snapshot_date, scanned_at, scan_note, distinct_items,
                           gold_mint_peg_g, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          scanned_at = VALUES(scanned_at),
                          scan_note = VALUES(scan_note),
                          distinct_items = VALUES(distinct_items),
                          gold_mint_peg_g = VALUES(gold_mint_peg_g),
                          updated_at = VALUES(updated_at)
                        """.formatted(prefix))) {
                    dailySummary.setString(1, serverId);
                    dailySummary.setObject(2, snapshotDate);
                    dailySummary.setLong(3, scannedAt);
                    dailySummary.setString(4, scanNote);
                    dailySummary.setInt(5, counts.size());
                    dailySummary.setDouble(6, goldMintPegG);
                    dailySummary.setTimestamp(7, now);
                    dailySummary.executeUpdate();
                }
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void replacePhysicalGold(
            String serverId, PhysicalGoldStorageScanner.ScanResult result, Instant scannedAt) throws SQLException {
        Timestamp timestamp = Timestamp.from(scannedAt);
        try (Connection connection = connections.get()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM " + prefix + "player_physical_gold WHERE server_id = ?")) {
                    delete.setString(1, serverId);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO %splayer_physical_gold
                          (server_id, minecraft_uuid, minecraft_username, inventory_g, ender_g,
                           shop_g, chest_g, towny_placed_g, total_g, scanned_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.formatted(prefix))) {
                    for (PhysicalGoldStorageScanner.PlayerRow row : result.players()) {
                        insert.setString(1, serverId);
                        insert.setString(2, row.uuid());
                        insert.setString(3, row.username());
                        insert.setDouble(4, row.inventoryG());
                        insert.setDouble(5, row.enderG());
                        insert.setDouble(6, row.shopG());
                        insert.setDouble(7, row.chestG());
                        insert.setDouble(8, row.townyPlacedG());
                        insert.setDouble(9, row.totalG());
                        insert.setTimestamp(10, timestamp);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                try (PreparedStatement summary = connection.prepareStatement("""
                        INSERT INTO %sphysical_gold_summary
                          (server_id, total_storage_g, unattributed_g, player_count, shops_scanned,
                           chunks_scanned, towny_blocks_scanned, scanned_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          total_storage_g = VALUES(total_storage_g),
                          unattributed_g = VALUES(unattributed_g),
                          player_count = VALUES(player_count),
                          shops_scanned = VALUES(shops_scanned),
                          chunks_scanned = VALUES(chunks_scanned),
                          towny_blocks_scanned = VALUES(towny_blocks_scanned),
                          scanned_at = VALUES(scanned_at),
                          updated_at = VALUES(updated_at)
                        """.formatted(prefix))) {
                    summary.setString(1, serverId);
                    summary.setDouble(2, result.totalG());
                    summary.setDouble(3, result.unattributedG());
                    summary.setInt(4, result.players().size());
                    summary.setInt(5, result.shopsScanned());
                    summary.setInt(6, result.chunksScanned());
                    summary.setInt(7, result.townyBlocksScanned());
                    summary.setTimestamp(8, timestamp);
                    summary.setTimestamp(9, timestamp);
                    summary.executeUpdate();
                }
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void upsertHostMetrics(
            String hostKey, Instant minuteStart, List<HostMetricsSample> samples) throws SQLException {
        if (samples.isEmpty()) {
            return;
        }
        double cpu = 0;
        double ram = 0;
        double disk = 0;
        double tps = 0;
        for (HostMetricsSample sample : samples) {
            cpu += sample.cpuPct;
            ram += sample.ramPct;
            disk += sample.diskUsedPct;
            tps += sample.tps;
        }
        int count = samples.size();
        try (Connection connection = connections.get();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO %shost_metrics_minute
                          (host_key, minute_ts, cpu_avg_pct, ram_avg_pct, disk_used_pct,
                           tps_avg, sample_count, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          cpu_avg_pct = VALUES(cpu_avg_pct),
                          ram_avg_pct = VALUES(ram_avg_pct),
                          disk_used_pct = VALUES(disk_used_pct),
                          tps_avg = VALUES(tps_avg),
                          sample_count = VALUES(sample_count),
                          updated_at = VALUES(updated_at)
                        """.formatted(prefix))) {
            statement.setString(1, hostKey);
            statement.setTimestamp(2, Timestamp.from(minuteStart));
            statement.setDouble(3, cpu / count);
            statement.setDouble(4, ram / count);
            statement.setDouble(5, disk / count);
            statement.setDouble(6, tps / count);
            statement.setInt(7, count);
            statement.setTimestamp(8, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
        try (Connection connection = connections.get();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO %shost_metrics_day
                          (host_key, metric_date, cpu_sum, ram_sum, disk_sum, tps_sum,
                           sample_total, minute_count, updated_at)
                        SELECT host_key, DATE(minute_ts),
                               SUM(cpu_avg_pct * sample_count),
                               SUM(ram_avg_pct * sample_count),
                               SUM(disk_used_pct * sample_count),
                               SUM(tps_avg * sample_count),
                               SUM(sample_count), COUNT(*), ?
                        FROM %shost_metrics_minute
                        WHERE host_key = ? AND DATE(minute_ts) = DATE(?)
                        GROUP BY host_key, DATE(minute_ts)
                        ON DUPLICATE KEY UPDATE
                          cpu_sum = VALUES(cpu_sum),
                          ram_sum = VALUES(ram_sum),
                          disk_sum = VALUES(disk_sum),
                          tps_sum = VALUES(tps_sum),
                          sample_total = VALUES(sample_total),
                          minute_count = VALUES(minute_count),
                          updated_at = VALUES(updated_at)
                        """.formatted(prefix, prefix))) {
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            statement.setString(2, hostKey);
            statement.setTimestamp(3, Timestamp.from(minuteStart));
            statement.executeUpdate();
        }
    }

    public void upsertServerStatus(
            String serverId, int onlinePlayers, String pluginVersion, String gameVersion) throws SQLException {
        try (Connection connection = connections.get();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO %sserver_status
                          (server_id, online_players, plugin_version, game_version, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          online_players = VALUES(online_players),
                          plugin_version = VALUES(plugin_version),
                          game_version = VALUES(game_version),
                          updated_at = VALUES(updated_at)
                        """.formatted(prefix))) {
            statement.setString(1, serverId);
            statement.setInt(2, Math.max(0, onlinePlayers));
            statement.setString(3, pluginVersion);
            statement.setString(4, gameVersion);
            statement.setTimestamp(5, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    public void replaceTownySnapshot(String serverId, Map<String, Object> snapshot) throws SQLException {
        List<?> towns = snapshot.get("towns") instanceof List<?> rows ? rows : List.of();
        List<?> nations = snapshot.get("nations") instanceof List<?> rows ? rows : List.of();
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection connection = connections.get()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement deleteTowns = connection.prepareStatement(
                                "DELETE FROM " + prefix + "towny_towns_snapshot WHERE server_id = ?");
                        PreparedStatement deleteNations = connection.prepareStatement(
                                "DELETE FROM " + prefix + "towny_nations_snapshot WHERE server_id = ?")) {
                    deleteTowns.setString(1, serverId);
                    deleteTowns.executeUpdate();
                    deleteNations.setString(1, serverId);
                    deleteNations.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO %stowny_towns_snapshot
                          (server_id, town_uuid, town_name, mayor_uuid, mayor_name, resident_count,
                           nation_name, is_capital, plot_count, town_balance_gold, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.formatted(prefix))) {
                    for (Object raw : towns) {
                        if (!(raw instanceof Map<?, ?> row) || text(row.get("town_uuid")).isBlank()) {
                            continue;
                        }
                        insert.setString(1, serverId);
                        insert.setString(2, text(row.get("town_uuid")));
                        insert.setString(3, text(row.get("town_name")));
                        setNullableString(insert, 4, row.get("mayor_uuid"));
                        setNullableString(insert, 5, row.get("mayor_name"));
                        insert.setInt(6, integer(row.get("resident_count")));
                        setNullableString(insert, 7, row.get("nation_name"));
                        insert.setBoolean(8, Boolean.TRUE.equals(row.get("is_capital")));
                        insert.setInt(9, integer(row.get("plot_count")));
                        insert.setDouble(10, decimal(row.get("town_balance_gold")));
                        insert.setTimestamp(11, now);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO %stowny_nations_snapshot
                          (server_id, nation_uuid, nation_name, leader_uuid, leader_name,
                           town_count, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """.formatted(prefix))) {
                    for (Object raw : nations) {
                        if (!(raw instanceof Map<?, ?> row) || text(row.get("nation_uuid")).isBlank()) {
                            continue;
                        }
                        insert.setString(1, serverId);
                        insert.setString(2, text(row.get("nation_uuid")));
                        insert.setString(3, text(row.get("nation_name")));
                        setNullableString(insert, 4, row.get("leader_uuid"));
                        setNullableString(insert, 5, row.get("leader_name"));
                        insert.setInt(6, integer(row.get("town_count")));
                        insert.setTimestamp(7, now);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private static void setNullableDouble(PreparedStatement statement, int index, Double value) throws SQLException {
        if (value == null || !Double.isFinite(value)) {
            statement.setNull(index, java.sql.Types.DOUBLE);
        } else {
            statement.setDouble(index, value);
        }
    }

    private static void setNullableString(PreparedStatement statement, int index, Object value) throws SQLException {
        String text = text(value);
        if (text.isBlank()) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        } else {
            statement.setString(index, text);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int integer(Object value) {
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }

    private static double decimal(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            return 0;
        }
        return Math.max(0, number.doubleValue());
    }
}
