package com.rootrecord.minecraft.rootstat.governance;

import com.rootrecord.minecraft.rootstat.RootStatBridge;
import com.rootrecord.minecraft.rootstat.cloud.CloudApiClient;
import com.rootrecord.minecraft.rootstat.mysql.MySqlPlayerStore;
import com.rootrecord.minecraft.rootstat.mysql.PlayerPlaytimeStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Local-first governance share: playtime(*) × max(1, listing votes) → % of 100.
 * Uses Official-synced MySQL so Claims↔Towny stay aligned without Cloudflare.
 */
public final class LocalGovernancePowerService {

    private static final long MIN_PLAYTIME_SECONDS = 3600L;

    private final RootStatBridge bridge;

    public LocalGovernancePowerService(RootStatBridge bridge) {
        this.bridge = bridge;
    }

    public CloudApiClient.GovernanceVotingPower resolve(UUID uuid) {
        if (uuid == null) {
            return unavailable();
        }
        try {
            CloudApiClient.GovernanceVotingPower local = computeLocal(uuid);
            if (local != null) {
                return local;
            }
        } catch (Exception ex) {
            bridge.getPlugin()
                    .getLogger()
                    .log(Level.FINE, "Local governance failed: " + ex.getMessage());
        }
        // Cloud refresh when reachable
        try {
            if (bridge.config().hasServerCredentials()) {
                return bridge.cloud().fetchGovernanceVotingPower(uuid.toString());
            }
        } catch (Exception ignored) {
        }
        return unavailable();
    }

    private CloudApiClient.GovernanceVotingPower computeLocal(UUID uuid) throws Exception {
        MySqlPlayerStore players = bridge.players();
        if (players == null) {
            return null;
        }
        String playtimeTable = bridge.config().playtimeTable();
        String playersTable = bridge.config().playersTable();
        String prefix = bridge.config().mysqlTablePrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "root_";
        }
        String votesTable = prefix + "rewards_votes";

        record Row(String uuid, long playtime, long votes, boolean eligible) {}
        List<Row> rows = new ArrayList<>();
        try (Connection c = players.openConnection()) {
            // Network total = max(*, sum of server scopes) so a stale/missing * row cannot zero out power.
            String sql =
                    """
                    SELECT p.uuid AS uuid,
                           COALESCE(pt.playtime, 0) AS playtime,
                           COALESCE(v.cnt, 0) AS votes,
                           (p.verified = 1 AND p.account_id IS NOT NULL AND p.account_id <> '') AS eligible
                    FROM %s p
                    LEFT JOIN (
                      SELECT uuid,
                             GREATEST(
                               COALESCE(MAX(CASE WHEN scope = ? THEN seconds END), 0),
                               COALESCE(SUM(CASE WHEN scope <> ? THEN seconds ELSE 0 END), 0)
                             ) AS playtime
                      FROM %s
                      GROUP BY uuid
                    ) pt ON LOWER(REPLACE(pt.uuid, '-', '')) = LOWER(REPLACE(p.uuid, '-', ''))
                    LEFT JOIN (
                      SELECT uuid, COUNT(*) AS cnt FROM %s GROUP BY uuid
                    ) v ON LOWER(REPLACE(v.uuid, '-', '')) = LOWER(REPLACE(p.uuid, '-', ''))
                    """
                            .formatted(playersTable, playtimeTable, votesTable);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, PlayerPlaytimeStore.SCOPE_GLOBAL);
                ps.setString(2, PlayerPlaytimeStore.SCOPE_GLOBAL);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Row(
                                rs.getString("uuid"),
                                rs.getLong("playtime"),
                                rs.getLong("votes"),
                                rs.getBoolean("eligible")));
                    }
                }
            }
        }

        // Patch this player from the same playtime source as /playtime when the JOIN under-reads,
        // and insert a row when rootstat_players is missing them entirely.
        String target = uuid.toString().toLowerCase(Locale.ROOT);
        String targetCompact = target.replace("-", "");
        long storePt = readPlaytime(uuid);
        long storeVotes = readVotes(uuid);
        boolean linked = players.findByUuid(uuid).map(p -> p.verified()).orElse(false);
        boolean patched = false;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.uuid == null) {
                continue;
            }
            String ru = r.uuid.toLowerCase(Locale.ROOT);
            if (!ru.equals(target) && !ru.replace("-", "").equals(targetCompact)) {
                continue;
            }
            long pt = Math.max(r.playtime, storePt);
            long votes = Math.max(r.votes, storeVotes);
            boolean elig = r.eligible || linked;
            rows.set(i, new Row(r.uuid, pt, votes, elig));
            patched = true;
            break;
        }
        if (!patched && (storePt > 0L || storeVotes > 0L || linked)) {
            rows.add(new Row(uuid.toString(), storePt, storeVotes, linked));
        }

        double totalRaw = 0;
        for (Row r : rows) {
            if (!r.eligible || r.playtime < MIN_PLAYTIME_SECONDS) {
                continue;
            }
            long votePoints = Math.max(1L, r.votes);
            totalRaw += (double) r.playtime * (double) votePoints;
        }

        Row self = null;
        for (Row r : rows) {
            if (r.uuid == null) {
                continue;
            }
            String ru = r.uuid.toLowerCase(Locale.ROOT);
            if (ru.equals(target) || ru.replace("-", "").equals(targetCompact)) {
                self = r;
                break;
            }
        }
        if (self == null) {
            // Player may have playtime but no rootstat_players row yet
            long pt = Math.max(storePt, 0L);
            long votes = Math.max(storeVotes, 0L);
            if (!linked) {
                return new CloudApiClient.GovernanceVotingPower(
                        true, false, 0, "link account", "", "https://rootmc.net/wiki/constitution/");
            }
            if (pt < MIN_PLAYTIME_SECONDS) {
                return new CloudApiClient.GovernanceVotingPower(
                        true,
                        false,
                        0,
                        "need more playtime (" + formatHours(pt) + ")",
                        "",
                        "https://rootmc.net/wiki/constitution/");
            }
            long vp = Math.max(1L, votes);
            double raw = (double) pt * (double) vp;
            double share = totalRaw > 0 ? (raw / (totalRaw + raw)) * 100.0 : 100.0;
            return new CloudApiClient.GovernanceVotingPower(
                    true,
                    true,
                    share,
                    String.format(Locale.US, "local %.3f%% · %s · %d votes", share, formatHours(pt), votes),
                    "",
                    "https://rootmc.net/wiki/constitution/");
        }

        if (!self.eligible) {
            return new CloudApiClient.GovernanceVotingPower(
                    true, false, 0, "link account", "", "https://rootmc.net/wiki/constitution/");
        }
        if (self.playtime < MIN_PLAYTIME_SECONDS) {
            return new CloudApiClient.GovernanceVotingPower(
                    true,
                    false,
                    0,
                    "need more playtime (" + formatHours(self.playtime) + ")",
                    "",
                    "https://rootmc.net/wiki/constitution/");
        }
        long votePoints = Math.max(1L, self.votes);
        double raw = (double) self.playtime * (double) votePoints;
        double share = totalRaw > 0 ? (raw / totalRaw) * 100.0 : 0;
        return new CloudApiClient.GovernanceVotingPower(
                true,
                true,
                share,
                String.format(
                        Locale.US,
                        "local %.3f%% · %s · %d votes",
                        share,
                        formatHours(self.playtime),
                        self.votes),
                "",
                "https://rootmc.net/wiki/constitution/");
    }

    private static String formatHours(long seconds) {
        if (seconds <= 0L) {
            return "0h";
        }
        long h = seconds / 3600L;
        long m = (seconds % 3600L) / 60L;
        if (h <= 0L) {
            return m + "m";
        }
        if (m <= 0L) {
            return h + "h";
        }
        return h + "h " + m + "m";
    }

    private long readPlaytime(UUID uuid) throws Exception {
        long fromStore = 0L;
        if (bridge.playtime() != null) {
            fromStore = bridge.playtime().totalSeconds(uuid).orElse(0L);
        }
        if (fromStore > 0L) {
            return fromStore;
        }
        // Fallback: same table/DB as votes (bridge.playtime() may be unset on some hosts).
        MySqlPlayerStore players = bridge.players();
        if (players == null) {
            return 0L;
        }
        String playtimeTable = bridge.config().playtimeTable();
        if (playtimeTable == null || playtimeTable.isBlank()) {
            return 0L;
        }
        String uuidMatch = "LOWER(REPLACE(uuid, '-', '')) = LOWER(REPLACE(?, '-', ''))";
        long star = -1L;
        long sumServers = 0L;
        try (Connection c = players.openConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT seconds FROM " + playtimeTable + " WHERE " + uuidMatch + " AND scope = '*' LIMIT 1")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        star = Math.max(0L, rs.getLong(1));
                    }
                }
            } catch (Exception ignored) {
                return 0L;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(SUM(seconds), 0) FROM " + playtimeTable
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
            return 0L;
        }
        return Math.max(star < 0L ? 0L : star, sumServers);
    }

    /** All-time listing votes for this UUID (local MySQL). */
    public long voteCount(UUID uuid) {
        try {
            return readVotes(uuid);
        } catch (Exception ex) {
            return 0L;
        }
    }

    private long readVotes(UUID uuid) throws Exception {
        MySqlPlayerStore players = bridge.players();
        if (players == null) {
            return 0;
        }
        String prefix = bridge.config().mysqlTablePrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "root_";
        }
        String votesTable = prefix + "rewards_votes";
        try (Connection c = players.openConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*) AS c FROM " + votesTable
                                + " WHERE LOWER(REPLACE(uuid, '-', '')) = LOWER(REPLACE(?, '-', ''))")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("c");
                }
            }
        } catch (Exception ex) {
            return 0;
        }
        return 0;
    }

    private static CloudApiClient.GovernanceVotingPower unavailable() {
        return new CloudApiClient.GovernanceVotingPower(false, false, 0, "unavailable", "", "");
    }
}
