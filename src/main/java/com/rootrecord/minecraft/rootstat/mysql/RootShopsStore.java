package com.rootrecord.minecraft.rootstat.mysql;

import com.rootrecord.minecraft.rootstat.config.RootStatConfig;
import com.rootrecord.minecraft.rootstat.economy.EconomySnapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** MySQL read model for Root Shops — website/cron pull source; updated incrementally from the game server. */
public final class RootShopsStore {

    private final RootStatConfig config;
    private final HikariConnectionSupplier connections;

    public RootShopsStore(RootStatConfig config, HikariConnectionSupplier connections) {
        this.config = config;
        this.connections = connections;
    }

    public void initSchema() throws SQLException {
        String listings = listingsTable();
        String averages = averagesTable();
        try (Connection c = connections.getConnection();
                PreparedStatement listingsPs = c.prepareStatement(
                        """
                        CREATE TABLE IF NOT EXISTS %s (
                          shop_id VARCHAR(96) PRIMARY KEY,
                          owner_uuid CHAR(36) NULL,
                          owner_username VARCHAR(16) NULL,
                          world_name VARCHAR(64) NOT NULL,
                          x INT NOT NULL,
                          y INT NOT NULL,
                          z INT NOT NULL,
                          item_key VARCHAR(64) NOT NULL,
                          price DOUBLE NOT NULL,
                          listing_type VARCHAR(16) NOT NULL DEFAULT 'sell',
                          stock_quantity INT NOT NULL DEFAULT 0,
                          synced_at DATETIME NOT NULL,
                          INDEX idx_rootshops_owner (owner_uuid),
                          INDEX idx_rootshops_item (item_key)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """
                                .formatted(listings));
                PreparedStatement averagesPs = c.prepareStatement(
                        """
                        CREATE TABLE IF NOT EXISTS %s (
                          item_key VARCHAR(64) PRIMARY KEY,
                          avg_price DOUBLE NOT NULL,
                          sample_count INT NOT NULL DEFAULT 0,
                          updated_at DATETIME NOT NULL
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """
                                .formatted(averages))) {
            listingsPs.executeUpdate();
            averagesPs.executeUpdate();
        }
        ensureStockQuantityColumn();
    }

    private void ensureStockQuantityColumn() throws SQLException {
        String listings = listingsTable();
        try (Connection c = connections.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT stock_quantity FROM " + listings + " LIMIT 1")) {
            ps.executeQuery();
        } catch (SQLException ex) {
            if (!isUnknownColumn(ex)) {
                throw ex;
            }
            try (Connection c = connections.getConnection();
                    PreparedStatement alter = c.prepareStatement(
                            "ALTER TABLE " + listings + " ADD COLUMN stock_quantity INT NOT NULL DEFAULT 0 AFTER listing_type")) {
                alter.executeUpdate();
            }
        }
    }

    private static boolean isUnknownColumn(SQLException ex) {
        String msg = ex.getMessage();
        return msg != null && (msg.contains("Unknown column") || msg.contains("doesn't exist"));
    }

    public void upsertListing(EconomySnapshot.ShopListingRow row) throws SQLException {
        if (row == null || row.shopId() == null || row.shopId().isBlank()) {
            return;
        }
        String listings = listingsTable();
        try (Connection c = connections.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        """
                        INSERT INTO %s
                          (shop_id, owner_uuid, owner_username, world_name, x, y, z, item_key, price, listing_type, stock_quantity, synced_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                        ON DUPLICATE KEY UPDATE
                          owner_uuid = VALUES(owner_uuid),
                          owner_username = VALUES(owner_username),
                          world_name = VALUES(world_name),
                          x = VALUES(x),
                          y = VALUES(y),
                          z = VALUES(z),
                          item_key = VALUES(item_key),
                          price = VALUES(price),
                          listing_type = VALUES(listing_type),
                          stock_quantity = VALUES(stock_quantity),
                          synced_at = NOW()
                        """
                                .formatted(listings))) {
            ps.setString(1, row.shopId());
            ps.setString(2, row.ownerUuid());
            ps.setString(3, row.ownerUsername());
            ps.setString(4, row.worldName());
            ps.setInt(5, row.x());
            ps.setInt(6, row.y());
            ps.setInt(7, row.z());
            ps.setString(8, row.itemKey());
            ps.setDouble(9, row.price());
            ps.setString(10, row.listingType() == null ? "sell" : row.listingType());
            ps.setInt(11, Math.max(0, row.stockQuantity()));
            ps.executeUpdate();
        }
        recomputeAverageForItem(row.itemKey());
    }

    public void deleteListing(String shopId, String itemKey) throws SQLException {
        if (shopId == null || shopId.isBlank()) {
            return;
        }
        String listings = listingsTable();
        try (Connection c = connections.getConnection();
                PreparedStatement ps = c.prepareStatement("DELETE FROM " + listings + " WHERE shop_id = ?")) {
            ps.setString(1, shopId);
            ps.executeUpdate();
        }
        if (itemKey != null && !itemKey.isBlank()) {
            recomputeAverageForItem(itemKey);
        }
    }

    private void recomputeAverageForItem(String itemKey) throws SQLException {
        if (itemKey == null || itemKey.isBlank()) {
            return;
        }
        String listings = listingsTable();
        String averages = averagesTable();
        double sum = 0;
        int count = 0;
        try (Connection c = connections.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        """
                        SELECT price FROM %s
                        WHERE item_key = ? AND listing_type = 'sell' AND stock_quantity > 0 AND price > 0
                        """
                                .formatted(listings))) {
            ps.setString(1, itemKey.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sum += rs.getDouble(1);
                    count++;
                }
            }
        }
        try (Connection c = connections.getConnection()) {
            if (count <= 0) {
                try (PreparedStatement del = c.prepareStatement("DELETE FROM " + averages + " WHERE item_key = ?")) {
                    del.setString(1, itemKey.toUpperCase());
                    del.executeUpdate();
                }
                return;
            }
            try (PreparedStatement upsert = c.prepareStatement(
                    """
                    INSERT INTO %s (item_key, avg_price, sample_count, updated_at)
                    VALUES (?, ?, ?, NOW())
                    ON DUPLICATE KEY UPDATE avg_price = VALUES(avg_price), sample_count = VALUES(sample_count), updated_at = NOW()
                    """
                            .formatted(averages))) {
                upsert.setString(1, itemKey.toUpperCase());
                upsert.setDouble(2, sum / count);
                upsert.setInt(3, count);
                upsert.executeUpdate();
            }
        }
    }

    /** @deprecated bulk replace — use {@link #upsertListing} / {@link #deleteListing} incrementally. */
    public void replaceSnapshot(EconomySnapshot snapshot) throws SQLException {
        if (snapshot == null) {
            return;
        }
        for (EconomySnapshot.ShopListingRow row : snapshot.shopListings()) {
            upsertListing(row);
        }
        for (EconomySnapshot.ShopPriceRow row : snapshot.shopPrices()) {
            if (row.prices().isEmpty()) {
                continue;
            }
            double sum = 0;
            for (double price : row.prices()) {
                sum += price;
            }
            String averages = averagesTable();
            try (Connection c = connections.getConnection();
                    PreparedStatement insertAvg = c.prepareStatement(
                            """
                            INSERT INTO %s (item_key, avg_price, sample_count, updated_at)
                            VALUES (?, ?, ?, NOW())
                            ON DUPLICATE KEY UPDATE avg_price = VALUES(avg_price), sample_count = VALUES(sample_count), updated_at = NOW()
                            """
                                    .formatted(averages))) {
                insertAvg.setString(1, row.itemKey());
                insertAvg.setDouble(2, sum / row.prices().size());
                insertAvg.setInt(3, row.prices().size());
                insertAvg.executeUpdate();
            }
        }
    }

    public void backfillListings(List<EconomySnapshot.ShopListingRow> rows) throws SQLException {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (EconomySnapshot.ShopListingRow row : rows) {
            upsertListing(row);
        }
    }

    private String listingsTable() {
        return config.mysqlTablePrefix() + "rootstat_shop_listings";
    }

    private String averagesTable() {
        return config.mysqlTablePrefix() + "rootstat_shop_price_avg";
    }

    @FunctionalInterface
    public interface HikariConnectionSupplier {
        Connection getConnection() throws SQLException;
    }
}
