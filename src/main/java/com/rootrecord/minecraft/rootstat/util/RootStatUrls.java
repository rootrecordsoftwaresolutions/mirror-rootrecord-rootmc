package com.rootrecord.minecraft.rootstat.util;

import java.util.UUID;

public final class RootStatUrls {

    public static final String DEFAULT_STATS_BASE = "https://rootmc.net/player";

    private RootStatUrls() {}

    public static String statsUrl(UUID uuid, String base) {
        String root = (base == null || base.isBlank()) ? DEFAULT_STATS_BASE : base.trim();
        root = root.replaceAll("/+$", "");
        return root + "?uuid=" + uuid;
    }

    public static String shopsUrl(String serverId, String shopsBase, UUID playerUuid) {
        String root = (shopsBase == null || shopsBase.isBlank())
                ? "https://rootmc.net/shops"
                : shopsBase.trim().replaceAll("/+$", "");
        StringBuilder url = new StringBuilder(root).append("?server=").append(serverId);
        if (playerUuid != null) {
            url.append("&player=").append(playerUuid);
        }
        return url.toString();
    }
}
