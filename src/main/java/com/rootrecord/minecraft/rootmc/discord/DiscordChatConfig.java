package com.rootrecord.minecraft.rootmc.discord;

import com.rootrecord.minecraft.common.config.RootMcDiscordConfig;
import com.rootrecord.minecraft.rootmc.RootMcPlugin;
import org.bukkit.configuration.file.FileConfiguration;

public final class DiscordChatConfig {

    private final boolean enabled;
    private final String inboundFormat;
    private final boolean relayChat;
    private final boolean relayJoin;
    private final boolean relayLeave;
    private final boolean relayDeath;
    private final boolean relayReachout;
    private final String botToken;
    private final String guildId;
    private final String channelId;
    private final String serverTag;
    private final String allowedRoleId;

    private DiscordChatConfig(
            boolean enabled,
            String inboundFormat,
            boolean relayChat,
            boolean relayJoin,
            boolean relayLeave,
            boolean relayDeath,
            boolean relayReachout,
            String botToken,
            String guildId,
            String channelId,
            String serverTag,
            String allowedRoleId) {
        this.enabled = enabled;
        this.inboundFormat = inboundFormat;
        this.relayChat = relayChat;
        this.relayJoin = relayJoin;
        this.relayLeave = relayLeave;
        this.relayDeath = relayDeath;
        this.relayReachout = relayReachout;
        this.botToken = botToken;
        this.guildId = guildId;
        this.channelId = channelId;
        this.serverTag = serverTag;
        this.allowedRoleId = allowedRoleId;
    }

    public static DiscordChatConfig from(RootMcPlugin plugin, FileConfiguration cfg) {
        RootMcDiscordConfig.DiscordSettings discord = RootMcDiscordConfig.resolve(plugin);
        String roleFromRootmc = cfg != null ? trim(cfg.getString("discord-chat.allowed-role-id")) : "";
        String allowedRole = !discord.linkedRoleId().isBlank() ? discord.linkedRoleId() : roleFromRootmc;
        return new DiscordChatConfig(
                cfg != null && cfg.getBoolean("discord-chat.enabled", false),
                cfg != null
                        ? cfg.getString(
                                "discord-chat.inbound-format",
                                "&8▎ &9Discord&8│ &f{user}&8 &7»&f {message}")
                        : "&8▎ &9Discord&8│ &f{user}&8 &7»&f {message}",
                cfg == null || cfg.getBoolean("discord-chat.relay-chat", true),
                cfg == null || cfg.getBoolean("discord-chat.relay-join", true),
                cfg == null || cfg.getBoolean("discord-chat.relay-leave", true),
                cfg == null || cfg.getBoolean("discord-chat.relay-death", true),
                cfg == null || cfg.getBoolean("discord-chat.relay-reachout", true),
                discord.botToken(),
                discord.guildId(),
                discord.ingameChatChannelId(),
                normalizeServerTag(cfg != null ? cfg.getString("discord-chat.server-tag", "T") : "T"),
                allowedRole);
    }

    private static String trim(String v) {
        return v == null ? "" : v.trim();
    }

    private static String normalizeServerTag(String raw) {
        if (raw == null || raw.isBlank()) {
            return "T";
        }
        String t = raw.trim().toUpperCase();
        if (t.equals("1") || t.equals("GEN1") || t.equals("G1") || t.equals("TOWNY")) {
            return "T";
        }
        if (t.equals("2") || t.equals("GEN2") || t.equals("G2") || t.equals("CLAIMS")) {
            return "C";
        }
        return t;
    }

    public boolean enabled() {
        return enabled;
    }

    public String inboundFormat() {
        return inboundFormat;
    }

    public boolean relayChat() {
        return relayChat;
    }

    public boolean relayJoin() {
        return relayJoin;
    }

    public boolean relayLeave() {
        return relayLeave;
    }

    public boolean relayDeath() {
        return relayDeath;
    }

    public boolean relayReachout() {
        return relayReachout;
    }

    public String botToken() {
        return botToken;
    }

    public String guildId() {
        return guildId;
    }

    public String channelId() {
        return channelId;
    }

    public String serverTag() {
        return serverTag;
    }

    public String allowedRoleId() {
        return allowedRoleId;
    }
}
