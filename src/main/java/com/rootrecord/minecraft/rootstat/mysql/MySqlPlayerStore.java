package com.rootrecord.minecraft.rootstat.mysql;

import com.rootrecord.minecraft.rootstat.config.RootStatConfig;
import com.rootrecord.minecraft.rootstat.model.LinkedPlayer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class MySqlPlayerStore {

    private final RootStatConfig config;
    private HikariDataSource dataSource;

    public MySqlPlayerStore(RootStatConfig config) {
        this.config = config;
        openPool();
    }

    private void openPool() {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.jdbcUrl());
        hc.setUsername(config.mysqlUsername());
        hc.setPassword(config.mysqlPassword());
        hc.setMaximumPoolSize(config.mysqlPoolSize());
        hc.setConnectionTimeout(5_000);
        hc.setInitializationFailTimeout(10_000);
        hc.setPoolName("RootStat");
        hc.addDataSourceProperty("cachePrepStmts", "true");
        dataSource = new HikariDataSource(hc);
    }

    public void initSchema() throws SQLException {
        String table = config.playersTable();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        """
                        CREATE TABLE IF NOT EXISTS %s (
                          uuid CHAR(36) PRIMARY KEY,
                          username VARCHAR(16) NOT NULL,
                          account_id VARCHAR(64) NULL,
                          email VARCHAR(255) NULL,
                          verified TINYINT(1) NOT NULL DEFAULT 0,
                          verified_at DATETIME NULL,
                          updated_at DATETIME NOT NULL,
                          INDEX idx_rootstat_account (account_id),
                          INDEX idx_rootstat_username (username)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """
                                .formatted(table))) {
            ps.executeUpdate();
        }
    }

    public void upsert(LinkedPlayer player) throws SQLException {
        String table = config.playersTable();
        boolean verified = player.verified();
        Timestamp verifiedAt = player.verifiedAt() == null ? null : Timestamp.from(Instant.parse(player.verifiedAt()));
        Timestamp updatedAt = player.updatedAt() == null
                ? Timestamp.from(Instant.now())
                : Timestamp.from(Instant.parse(player.updatedAt()));

        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        """
                        INSERT INTO %s (uuid, username, account_id, email, verified, verified_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          username = VALUES(username),
                          account_id = VALUES(account_id),
                          email = VALUES(email),
                          verified = VALUES(verified),
                          verified_at = VALUES(verified_at),
                          updated_at = VALUES(updated_at)
                        """
                                .formatted(table))) {
            ps.setString(1, player.uuid());
            ps.setString(2, player.username() == null ? "Unknown" : player.username());
            ps.setString(3, player.accountId());
            ps.setString(4, player.email());
            ps.setBoolean(5, verified);
            ps.setTimestamp(6, verifiedAt);
            ps.setTimestamp(7, updatedAt);
            ps.executeUpdate();
        }
    }

    public Optional<LinkedPlayer> findByUuid(UUID uuid) throws SQLException {
        String table = config.playersTable();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT uuid, username, account_id, email, verified_at, updated_at FROM "
                                + table
                                + " WHERE LOWER(REPLACE(uuid, '-', '')) = LOWER(REPLACE(?, '-', '')) LIMIT 1")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new LinkedPlayer(
                                rs.getString("uuid"),
                                rs.getString("username"),
                                rs.getString("account_id"),
                                rs.getString("email"),
                                rs.getTimestamp("verified_at") == null
                                        ? null
                                        : rs.getTimestamp("verified_at").toInstant().toString(),
                                rs.getTimestamp("updated_at").toInstant().toString()));
            }
        }
    }

    public String latestUpdatedAtIso() throws SQLException {
        String table = config.playersTable();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT MAX(updated_at) AS max_u FROM " + table);
                ResultSet rs = ps.executeQuery()) {
            if (rs.next() && rs.getTimestamp("max_u") != null) {
                return rs.getTimestamp("max_u").toInstant().toString();
            }
        }
        return null;
    }

    public Connection openConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
