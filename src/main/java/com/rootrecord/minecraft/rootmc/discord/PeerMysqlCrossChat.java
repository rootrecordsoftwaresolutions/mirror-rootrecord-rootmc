package com.rootrecord.minecraft.rootmc.discord;

import com.rootrecord.minecraft.common.config.RootMcDatabaseConfig;
import com.rootrecord.minecraft.rootmc.RootMcPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Claims↔Towny chat over peer MySQL (Official peer JDBC) — no Cloudflare.
 * Local table + pull from peer each poll.
 */
public final class PeerMysqlCrossChat {

    public record ChatRow(long id, String tag, String username, String message, Instant at) {}

    private final RootMcPlugin plugin;
    private String localJdbc = "";
    private String localUser = "";
    private String localPass = "";
    private String peerJdbc = "";
    private String peerUser = "";
    private String peerPass = "";
    private String localTable = "root_rootmc_cross_chat";
    private String peerTable = "root_rootmc_cross_chat";
    private String tag = "C";
    private long lastId;

    public PeerMysqlCrossChat(RootMcPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean configure(FileConfiguration rootmcCfg, String tag, long lastId) {
        this.tag = tag;
        this.lastId = lastId;
        RootMcDatabaseConfig.DatabaseSettings local = RootMcDatabaseConfig.resolve(plugin, rootmcCfg);
        if (local == null || !local.isConfigured()) {
            return false;
        }
        localJdbc = local.jdbcUrl();
        localUser = local.username();
        localPass = local.password();
        String prefix = local.tablePrefix() == null || local.tablePrefix().isBlank() ? "root_" : local.tablePrefix();
        localTable = prefix + "rootmc_cross_chat";

        // Prefer Official peer block
        File official = new File(com.rootrecord.minecraft.common.RootRecordFolders.dir(plugin), "rootmc-official.yml");
        if (!official.isFile()) {
            return false;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(official);
        String host = yml.getString("peer.host", "");
        int port = yml.getInt("peer.port", 3306);
        String db = yml.getString("peer.database", "");
        peerUser = yml.getString("peer.username", "");
        peerPass = yml.getString("peer.password", "");
        String peerPrefix = yml.getString("peer.table-prefix", "root_");
        if (peerPrefix == null || peerPrefix.isBlank()) {
            peerPrefix = "root_";
        }
        peerTable = peerPrefix + "rootmc_cross_chat";
        if (host.isBlank() || db.isBlank() || peerUser.isBlank()) {
            return false;
        }
        String params = yml.getString(
                "peer.jdbc-params",
                RootMcDatabaseConfig.DEFAULT_JDBC_PARAMS);
        peerJdbc = "jdbc:mysql://" + host + ":" + port + "/" + db + "?" + params;
        try {
            ensureTable(localJdbc, localUser, localPass, localTable);
            ensureTable(peerJdbc, peerUser, peerPass, peerTable);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Peer cross-chat schema failed: " + ex.getMessage());
            return false;
        }
        return true;
    }

    public void setLastId(long id) {
        this.lastId = id;
    }

    public long lastId() {
        return lastId;
    }

    public void post(String username, String message) throws Exception {
        Instant now = Instant.now();
        insert(localJdbc, localUser, localPass, localTable, tag, username, message, now);
        try {
            insert(peerJdbc, peerUser, peerPass, peerTable, tag, username, message, now);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.FINE, "Peer cross-chat remote insert failed: " + ex.getMessage());
        }
    }

    public void heartbeat(String status) throws Exception {
        // Presence row with empty message + username=__presence__
        post("__presence__:" + status, status);
    }

    public List<ChatRow> poll() throws Exception {
        // Pull peer → local, then read local > lastId excluding own tag for chat display
        pullPeerIntoLocal();
        List<ChatRow> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(localJdbc, localUser, localPass);
                PreparedStatement ps = c.prepareStatement(
                        "SELECT id, tag, username, message, created_at FROM " + localTable
                                + " WHERE id > ? ORDER BY id ASC LIMIT 100")) {
            ps.setLong(1, lastId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    lastId = Math.max(lastId, id);
                    String rowTag = rs.getString("tag");
                    String user = rs.getString("username");
                    String msg = rs.getString("message");
                    Timestamp ts = rs.getTimestamp("created_at");
                    if (user != null && user.startsWith("__presence__:")) {
                        continue;
                    }
                    if (tag.equalsIgnoreCase(rowTag)) {
                        continue; // own
                    }
                    out.add(new ChatRow(
                            id,
                            rowTag,
                            user,
                            msg,
                            ts != null ? ts.toInstant() : Instant.now()));
                }
            }
        }
        return out;
    }

    /**
     * Pull peer rows, then jump {@code lastId} to {@code MAX(id)} so a cold cursor
     * (last-id=0) cannot stream days of history 100 rows at a time.
     */
    public long seekToEnd() throws Exception {
        pullPeerIntoLocal();
        try (Connection c = DriverManager.getConnection(localJdbc, localUser, localPass);
                PreparedStatement ps = c.prepareStatement(
                        "SELECT COALESCE(MAX(id), 0) FROM " + localTable);
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                lastId = Math.max(lastId, rs.getLong(1));
            }
        }
        return lastId;
    }

    private void pullPeerIntoLocal() throws Exception {
        // Sync by peer id watermark via local lastId is done in poll/seekToEnd.
        // Time window is a safety net only — prefer NOW() (session TZ) over UTC_TIMESTAMP()
        // so DATETIME values written by JDBC Timestamp match the filter.
        try (Connection peer = DriverManager.getConnection(peerJdbc, peerUser, peerPass);
                PreparedStatement ps = peer.prepareStatement(
                        "SELECT tag, username, message, created_at FROM " + peerTable
                                + " WHERE created_at > (NOW() - INTERVAL 2 MINUTE) ORDER BY id ASC LIMIT 200");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                insertIgnore(
                        localJdbc,
                        localUser,
                        localPass,
                        localTable,
                        rs.getString("tag"),
                        rs.getString("username"),
                        rs.getString("message"),
                        rs.getTimestamp("created_at"));
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.FINE, "Peer cross-chat pull failed: " + ex.getMessage());
        }
    }

    private static void ensureTable(String jdbc, String user, String pass, String table) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbc, user, pass);
                Statement st = c.createStatement()) {
            st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS %s (
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      tag VARCHAR(8) NOT NULL,
                      username VARCHAR(32) NOT NULL,
                      message VARCHAR(512) NOT NULL,
                      created_at DATETIME NOT NULL,
                      UNIQUE KEY uq_cross_chat (tag, username, message, created_at),
                      INDEX idx_cross_chat_id (id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """
                            .formatted(table));
        }
    }

    private static void insert(
            String jdbc, String user, String pass, String table, String tag, String username, String message, Instant at)
            throws Exception {
        try (Connection c = DriverManager.getConnection(jdbc, user, pass);
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO " + table + " (tag, username, message, created_at) VALUES (?,?,?,?)")) {
            ps.setString(1, tag);
            ps.setString(2, username);
            ps.setString(3, message);
            ps.setTimestamp(4, Timestamp.from(at));
            ps.executeUpdate();
        }
    }

    private static void insertIgnore(
            String jdbc,
            String user,
            String pass,
            String table,
            String tag,
            String username,
            String message,
            Timestamp at)
            throws Exception {
        try (Connection c = DriverManager.getConnection(jdbc, user, pass);
                PreparedStatement ps = c.prepareStatement(
                        "INSERT IGNORE INTO " + table + " (tag, username, message, created_at) VALUES (?,?,?,?)")) {
            ps.setString(1, tag);
            ps.setString(2, username);
            ps.setString(3, message);
            ps.setTimestamp(4, at != null ? at : Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }
}
