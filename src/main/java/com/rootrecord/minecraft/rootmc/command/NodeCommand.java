package com.rootrecord.minecraft.rootmc.command;

import com.rootrecord.minecraft.common.ChatUi;
import com.rootrecord.minecraft.common.FancyHeadlines;
import com.rootrecord.minecraft.rootmc.RootMcPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

/** Shows Server Data Relay connected vs fallback servers (Cloudflare). */
public final class NodeCommand implements CommandExecutor, TabCompleter {

    private final RootMcPlugin plugin;

    public NodeCommand(RootMcPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("rootmc.node")) {
            sender.sendMessage(plugin.colorize("&cYou don't have permission."));
            return true;
        }
        FancyHeadlines.sendBanner(sender, "Node");
        var pref = plugin.connectionPreference();
        if (pref == null) {
            ChatUi.entry(sender, "Status", "unavailable", "alert");
            return true;
        }
        String provider = pref.activeProvider();
        if ("solar".equals(provider) || "rootmc".equals(provider)) {
            sender.sendMessage(plugin.colorize(pref.relayConnectedMessage()));
        } else if ("cloudflare".equals(provider)) {
            sender.sendMessage(plugin.colorize(pref.relayFallbackMessage()));
        } else {
            ChatUi.entry(sender, "Relay", "unknown", "alert");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
