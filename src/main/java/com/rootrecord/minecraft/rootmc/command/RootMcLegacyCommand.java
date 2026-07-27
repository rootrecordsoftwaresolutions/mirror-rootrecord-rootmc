package com.rootrecord.minecraft.rootmc.command;

import com.rootrecord.minecraft.rootmc.RootMcPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

public final class RootMcLegacyCommand implements CommandExecutor, TabCompleter {

    private final RootMcPlugin plugin;

    public RootMcLegacyCommand(RootMcPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return handleStatus(sender);
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> handleStatus(sender);
            case "reload" -> handleReload(sender);
            default -> {
                sender.sendMessage(plugin.colorize("&eUsage: /rootmc [status|reload]"));
                yield true;
            }
        };
    }

    private boolean handleStatus(CommandSender sender) {
        var cfg = plugin.rootMcConfig();
        sender.sendMessage(plugin.colorize(
                "&7RootMC &f" + plugin.getDescription().getVersion()
                        + " &7— world &f" + cfg.defaultWorldName()
                        + " &7@ &f" + cfg.serverAddress()));
        if (plugin.connectionPreference() != null) {
            sender.sendMessage(plugin.colorize(plugin.connectionPreference().statusLine()));
        }
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("rootmc.reload")) {
            sender.sendMessage(plugin.colorize("&cNo permission."));
            return true;
        }
        plugin.reloadRootStatConfig();
        plugin.heartbeatTask().start();
        plugin.syncTask().start();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, plugin.heartbeatTask()::runSafe);
        sender.sendMessage(plugin.colorize("&aRootMC config reloaded."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("status", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }
}
