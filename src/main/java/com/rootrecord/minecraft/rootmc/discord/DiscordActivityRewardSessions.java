package com.rootrecord.minecraft.rootmc.discord;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks which players already received the Discord activity reward broadcast this login session. */
public final class DiscordActivityRewardSessions {

    private static final Set<UUID> BROADCAST_THIS_SESSION = ConcurrentHashMap.newKeySet();

    private DiscordActivityRewardSessions() {}

    /** @return true when this is the first broadcast for the player this session */
    public static boolean markBroadcast(UUID playerId) {
        return BROADCAST_THIS_SESSION.add(playerId);
    }

    public static void clearSession(UUID playerId) {
        BROADCAST_THIS_SESSION.remove(playerId);
    }
}
