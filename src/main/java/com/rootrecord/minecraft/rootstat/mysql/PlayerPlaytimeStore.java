package com.rootrecord.minecraft.rootstat.mysql;

import com.rootrecord.minecraft.rootstat.config.RootStatConfig;
import com.rootrecord.minecraft.rootstat.model.PlayerPlaytimeRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Fallback playtime store when Root-Times is absent. Same scoped schema as Times. */
public final class PlayerPlaytimeStore {

    public static final String SCOPE_GLOBAL = "*";

    private final RootStatConfig config;
    private final Supplier<Connection> connectionSupplier;

    public PlayerPlaytimeStore(RootStatConfig config, Supplier<Connection> connectionSupplier) {
        this.config = config;
        this.connectionSupplier = connectionSupplier;
    }

    public void initSchema() throws SQLException {
        String table = config.playtimeTable();
        String monthly = config.playtimeMonthlyTable();
        try (Connection c = connectionSupplier.get();
                PreparedStatement ps = c.prepareStatement(
                        """
                        CREATE TABLE IF NOT EXISTS %s (
                          uuid CHAR(36) NOT NULL,
                          scope VARCHAR(64) NOT NULL,
                          username VARCHAR(16) NOT NULL,
                          seconds BIGINT NOT NULL DEFAULT 0,
                          first_join_at DATETIME NOT NULL,
                          last_login_at DATETIME NOT NULL,
                          updated_at DATETIME NOT NULL,
                          PRIMARY KEY (uuid, scope),
                          INDEX idx_playtime_scope (scope, seconds),
                          INDEX idx_playtime_username (username)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """
                                .formatted(table));
                PreparedStatement monthlyPs = c.prepareStatement(
                        """
                        CREATE TABLE IF NOT EXISTS %s (
                          uuid CHAR(36) NOT NULL,
                          scope VARCHAR(64) NOT NULL,
                          month_key CHAR(7) NOT NULL,
                          seconds BIGINT NOT NULL DEFAULT 0,
                          updated_at DATETIME NOT NULL,
                          PRIMARY KEY (uuid, scope, month_key),
                          INDEX idx_playtime_month (scope, month_key, seconds)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """
                                .formatted(monthly))) {
            ps.executeUpdate();
            monthlyPs.executeUpdate();
        }
    }

    public void recordLogin(UUID uuid, String username) throws SQLException {
        Timestamp now = Timestamp.from(Instant.now());
        String name = username == null ? "Unknown" : username;
        upsertLogin(uuid, SCOPE_GLOBAL, name, now);
        String serverId = config.serverId();
        if (serverId != null && !serverId.isBlank()) {
            upsertLogin(uuid, serverId, name, now);
        }
    }

    private void upsertLogin(UUID uuid, String scope, String username, Timestamp now) throws SQLException {
        String table = config.playtimeTable();
        try (Connection c = connectionSupplier.get();
                PreparedStatement ps = c.prepareStatement(
                        """
                        INSERT INTO %s (uuid, scope, username, seconds, first_join_at, last_login_at, updated_at)
                        VALUES (?, ?, ?, 0, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          username = VALUES(username),
                          last_login_at = VALUES(last_login_at),
                          updated_at = VALUES(updated_at)
                        """
                                .formatted(table))) {
            ps.setString(1, uuid.toString());
            ps.setString(2, scope);
            ps.setString(3, username);
            ps.setTimestamp(4, now);
            ps.setTimestamp(5, now);
            ps.setTimestamp(6, now);
            ps.executeUpdate();
        }
    }

    public void addSession(UUID uuid, long sessionSeconds) throws SQLException {
        if (sessionSeconds <= 0) {
            return;
        }
        String monthKey = java.time.YearMonth.now(java.time.ZoneId.of("Pacific/Honolulu")).toString();
        Timestamp now = Timestamp.from(Instant.now());
        addSeconds(uuid, SCOPE_GLOBAL, sessionSeconds, now);
        String serverId = config.serverId();
        if (serverId != null && !serverId.isBlank()) {
            addSeconds(uuid, serverId, sessionSeconds, now);
        }
        addMonthly(uuid, SCOPE_GLOBAL, monthKey, sessionSeconds, now);
        if (serverId != null && !serverId.isBlank()) {
            addMonthly(uuid, serverId, monthKey, sessionSeconds, now);
        }
    }

    private void addSeconds(UUID uuid, String scope, long seconds, Timestamp now) throws SQLException {
        String table = config.playtimeTable();
        try (Connection c = connectionSupplier.get();
                PreparedStatement ps = c.prepareStatement(
                        """
                        INSERT INTO %s (uuid, scope, username, seconds, first_join_at, last_login_at, updated_at)
                        VALUES (?, ?, 'Unknown', ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          seconds = seconds + VALUES(seconds),
                          updated_at = VALUES(updated_at)
                        """
                                .formatted(table))) {
            ps.setString(1, uuid.toString());
            ps.setString(2, scope);
            ps.setLong(3, seconds);
            ps.setTimestamp(4, now);
            ps.setTimestamp(5, now);
            ps.setTimestamp(6, now);
            ps.executeUpdate();
        }
    }

    private void addMonthly(UUID uuid, String scope, String monthKey, long seconds, Timestamp now)
            throws SQLException {
        String monthly = config.playtimeMonthlyTable();
        try (Connection c = connectionSupplier.get();
                PreparedStatement monthlyPs = c.prepareStatement(
                        """
                        INSERT INTO %s (uuid, scope, month_key, seconds, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          seconds = seconds + VALUES(seconds),
                          updated_at = VALUES(updated_at)
                        """
                                .formatted(monthly))) {
            monthlyPs.setString(1, uuid.toString());
            monthlyPs.setString(2, scope);
            monthlyPs.setString(3, monthKey);
            monthlyPs.setLong(4, seconds);
            monthlyPs.setTimestamp(5, now);
            monthlyPs.executeUpdate();
        }
    }

    public long monthlySeconds(UUID uuid, String monthKey) throws SQLException {
        String monthly = config.playtimeMonthlyTable();
        try (Connection c = connectionSupplier.get();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT seconds FROM "
                                + monthly
                                + " WHERE uuid = ? AND scope = ? AND month_key = ? LIMIT 1")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, SCOPE_GLOBAL);
            ps.setString(3, monthKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public List<MonthlyPlaytimeRow> readMonth(String monthKey) throws SQLException {
        String monthly = config.playtimeMonthlyTable();
        List<MonthlyPlaytimeRow> out = new ArrayList<>();
        try (Connection c = connectionSupplier.get();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT uuid, seconds FROM " + monthly + " WHERE scope = ? AND month_key = ?")) {
            ps.setString(1, SCOPE_GLOBAL);
            ps.setString(2, monthKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new MonthlyPlaytimeRow(rs.getString("uuid"), rs.getLong("seconds")));
                }
            }
        }
        return out;
    }

    public record MonthlyPlaytimeRow(String uuid, long playtimeSeconds) {}

    public record MonthlyPlaytimeKeyedRow(String uuid, String monthKey, long playtimeSeconds) {}

    public List<MonthlyPlaytimeKeyedRow> readCurrentAndPriorMonths() throws SQLException {
        String current = java.time.YearMonth.now(java.time.ZoneId.of("Pacific/Honolulu")).toString();
        String prior = java.time.YearMonth.now(java.time.ZoneId.of("Pacific/Honolulu")).minusMonths(1).toString();
        List<MonthlyPlaytimeKeyedRow> out = new ArrayList<>();
        appendMonth(out, current);
        appendMonth(out, prior);
        return out;
    }

    private void appendMonth(List<MonthlyPlaytimeKeyedRow> out, String monthKey) throws SQLException {
        for (MonthlyPlaytimeRow row : readMonth(monthKey)) {
            out.add(new MonthlyPlaytimeKeyedRow(row.uuid(), monthKey, row.playtimeSeconds()));
        }
    }

    public List<PlayerPlaytimeRecord> readAll() throws SQLException {
        String table = config.playtimeTable();
        List<PlayerPlaytimeRecord> out = new ArrayList<>();
        try (Connection c = connectionSupplier.get();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT uuid, username, seconds, first_join_at, last_login_at FROM "
                                + table
                                + " WHERE scope = '*'");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Timestamp first = rs.getTimestamp("first_join_at");
                Timestamp last = rs.getTimestamp("last_login_at");
                out.add(
                        new PlayerPlaytimeRecord(
                                rs.getString("uuid"),
                                rs.getString("username"),
                                rs.getLong("seconds"),
                                first != null ? first.toInstant().toString() : null,
                                last != null ? last.toInstant().toString() : null));
            }
        }
        return out;
    }

    public Optional<Long> totalSeconds(UUID uuid) throws SQLException {
        String table = config.playtimeTable();
        long star = -1L;
        long sumServers = 0L;
        // Match dashed or dashless uuid storage (Claims/Towny/Official sync variants).
        String uuidMatch = "LOWER(REPLACE(uuid, '-', '')) = LOWER(REPLACE(?, '-', ''))";
        try (Connection c = connectionSupplier.get()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT seconds FROM " + table + " WHERE " + uuidMatch + " AND scope = '*' LIMIT 1")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        star = Math.max(0L, rs.getLong(1));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(SUM(seconds), 0) FROM " + table
                            + " WHERE " + uuidMatch + " AND scope <> '*'")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        sumServers = Math.max(0L, rs.getLong(1));
                    }
                }
            }
        }
        if (star < 0L && sumServers <= 0L) {
            return Optional.empty();
        }
        return Optional.of(Math.max(star < 0L ? 0L : star, sumServers));
    }

    public Optional<PlayerPlaytimeRecord> findByUsername(String username) throws SQLException {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String table = config.playtimeTable();
        try (Connection c = connectionSupplier.get();
                PreparedStatement ps = c.prepareStatement(
                        """
                        SELECT uuid, username, seconds, first_join_at, last_login_at FROM %s
                        WHERE scope = '*' AND LOWER(username) = LOWER(?) LIMIT 1
                        """
                                .formatted(table))) {
            ps.setString(1, username.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Timestamp first = rs.getTimestamp("first_join_at");
                Timestamp last = rs.getTimestamp("last_login_at");
                return Optional.of(
                        new PlayerPlaytimeRecord(
                                rs.getString("uuid"),
                                rs.getString("username"),
                                rs.getLong("seconds"),
                                first != null ? first.toInstant().toString() : null,
                                last != null ? last.toInstant().toString() : null));
            }
        }
    }
}
