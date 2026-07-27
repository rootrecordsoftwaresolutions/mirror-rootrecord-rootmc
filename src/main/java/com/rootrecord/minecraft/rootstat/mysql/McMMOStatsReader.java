package com.rootrecord.minecraft.rootstat.mysql;

import com.rootrecord.minecraft.rootstat.config.RootStatConfig;
import com.rootrecord.minecraft.rootstat.model.McMMOPlayerSnapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Reads mcMMO skill levels from the shared MySQL database ({@code mcmmo_users} + {@code mcmmo_skills}).
 */
public final class McMMOStatsReader {

    private static final String[] SKILL_COLUMNS = {
        "mining",
        "woodcutting",
        "repair",
        "unarmed",
        "herbalism",
        "excavation",
        "archery",
        "swords",
        "axes",
        "acrobatics",
        "taming",
        "fishing",
        "alchemy",
        "crossbows",
        "tridents",
        "maces",
        "spears",
    };

    private static final String[] LEGACY_SKILL_COLUMNS = {
        "mining",
        "woodcutting",
        "repair",
        "unarmed",
        "herbalism",
        "excavation",
        "archery",
        "swords",
        "axes",
        "acrobatics",
        "taming",
        "fishing",
        "alchemy",
    };

    private final RootStatConfig config;
    private final Supplier<Connection> connectionSupplier;

    public McMMOStatsReader(RootStatConfig config, Supplier<Connection> connectionSupplier) {
        this.config = config;
        this.connectionSupplier = connectionSupplier;
    }

    public List<McMMOPlayerSnapshot> readAll() throws SQLException {
        try {
            return query(SKILL_COLUMNS);
        } catch (SQLException modernEx) {
            try {
                return query(LEGACY_SKILL_COLUMNS);
            } catch (SQLException legacyEx) {
                legacyEx.addSuppressed(modernEx);
                throw legacyEx;
            }
        }
    }

    public Optional<Integer> powerByUsername(String username) throws SQLException {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        try {
            return powerByUsername(username, SKILL_COLUMNS);
        } catch (SQLException modernEx) {
            try {
                return powerByUsername(username, LEGACY_SKILL_COLUMNS);
            } catch (SQLException legacyEx) {
                legacyEx.addSuppressed(modernEx);
                throw legacyEx;
            }
        }
    }

    private Optional<Integer> powerByUsername(String username, String[] skillColumns) throws SQLException {
        String users = config.mcmmoTablePrefix() + "users";
        String skills = config.mcmmoTablePrefix() + "skills";
        StringBuilder select = new StringBuilder("SELECT ");
        for (int i = 0; i < skillColumns.length; i++) {
            if (i > 0) {
                select.append(" + ");
            }
            select.append("s.").append(skillColumns[i]);
        }
        select.append(" AS power FROM ").append(users).append(" u ");
        select.append("INNER JOIN ").append(skills).append(" s ON u.id = s.user_id ");
        select.append("WHERE LOWER(u.`user`) = LOWER(?) LIMIT 1");

        try (Connection c = connectionSupplier.get();
                PreparedStatement ps = c.prepareStatement(select.toString())) {
            ps.setString(1, username.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(Math.max(0, rs.getInt("power")));
            }
        }
    }

    private List<McMMOPlayerSnapshot> query(String[] skillColumns) throws SQLException {
        String users = config.mcmmoTablePrefix() + "users";
        String skills = config.mcmmoTablePrefix() + "skills";

        StringBuilder select = new StringBuilder("SELECT u.uuid, u.`user` AS username");
        for (String col : skillColumns) {
            select.append(", s.").append(col).append(" AS ").append(col);
        }
        select.append(" FROM ").append(users).append(" u ");
        select.append("INNER JOIN ").append(skills).append(" s ON u.id = s.user_id ");
        select.append("WHERE u.uuid IS NOT NULL AND u.uuid <> ''");

        List<McMMOPlayerSnapshot> out = new ArrayList<>();
        try (Connection c = connectionSupplier.get();
                PreparedStatement ps = c.prepareStatement(select.toString());
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String uuid = normalizeUuid(rs.getString("uuid"));
                if (uuid == null) {
                    continue;
                }
                String username = rs.getString("username");
                Map<String, Integer> skillMap = new LinkedHashMap<>();
                int power = 0;
                for (String col : skillColumns) {
                    int level = Math.max(0, rs.getInt(col));
                    skillMap.put(col, level);
                    power += level;
                }
                out.add(new McMMOPlayerSnapshot(uuid, username, power, skillMap));
            }
        }
        return out;
    }

    private static String normalizeUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String uuid = raw.trim().toLowerCase(Locale.ROOT);
        if (!uuid.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            return null;
        }
        return uuid;
    }
}
