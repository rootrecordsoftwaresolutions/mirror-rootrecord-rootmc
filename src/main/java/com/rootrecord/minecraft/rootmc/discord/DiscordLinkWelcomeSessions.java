package com.rootrecord.minecraft.rootmc.discord;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks which players already received the Discord link welcome this login session. */
public final class DiscordLinkWelcomeSessions {

    private static final Set<UUID> WELCOMED_THIS_SESSION = ConcurrentHashMap.newKeySet();

    private DiscordLinkWelcomeSessions() {}

    /** @return true when this is the first welcome for the player this session */
    public static boolean markWelcome(UUID playerId) {
        return WELCOMED_THIS_SESSION.add(playerId);
    }

    public static void clearSession(UUID playerId) {
        WELCOMED_THIS_SESSION.remove(playerId);
    }
}
