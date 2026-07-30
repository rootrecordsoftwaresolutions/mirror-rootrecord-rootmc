package com.rootrecord.minecraft.rootstat.command;



import com.rootrecord.minecraft.common.GoldMoney;
import com.rootrecord.minecraft.common.ChatLinks;
import com.rootrecord.minecraft.common.RootMcMapUrls;
import com.rootrecord.minecraft.common.RootMcEconomyResolver;
import com.rootrecord.minecraft.common.RootMcTreasuryResolver;
import com.rootrecord.minecraft.common.ShadedServiceBridge;
import com.rootrecord.minecraft.rootmc.RootMcPlugin;
import com.rootrecord.minecraft.rootstat.RootStatBridge;

import com.rootrecord.minecraft.rootstat.cloud.CloudApiClient;

import com.rootrecord.minecraft.rootstat.economy.MarketValueReport;

import com.rootrecord.minecraft.rootstat.model.LinkStartResult;

import com.rootrecord.minecraft.rootstat.model.LinkedPlayer;

import com.rootrecord.minecraft.rootstat.util.RootStatUrls;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;

import org.bukkit.command.CommandExecutor;

import org.bukkit.command.CommandSender;

import org.bukkit.command.TabCompleter;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;



import java.util.List;

import java.util.Locale;

import java.util.Optional;
import java.util.UUID;



public final class RootStatCommand implements CommandExecutor, TabCompleter {



    private final RootStatBridge bridge;
    private final ValueCommand valueCommand;

    public RootStatCommand(RootStatBridge bridge) {
        this.bridge = bridge;
        this.valueCommand = new ValueCommand(bridge);
    }



    @Override

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (args.length == 0) {

            return handleStatus(sender);

        }



        String sub = args[0].toLowerCase(Locale.ROOT);

        return switch (sub) {

            case "link" -> handleLink(sender);

            case "status" -> handleStatus(sender);

            case "sync" -> handleSync(sender);

            case "reload" -> handleReload(sender);

            case "shops" -> handleShops(sender);

            case "map" -> handleMap(sender);

            case "value" -> handleValue(sender, args);

            case "deathfee" -> handleDeathFee(sender, args);

            default -> {

                sender.sendMessage(bridge.colorize("&eUsage: /" + label + " [link|status|map|shops|value|sync|reload|deathfee]"));

                yield true;

            }

        };

    }



    private boolean handleLink(CommandSender sender) {

        if (!(sender instanceof Player player)) {

            sender.sendMessage("Players only.");

            return true;

        }

        if (!sender.hasPermission("rootstat.link")) {

            sender.sendMessage(bridge.colorize(bridge.msg("no-permission")));

            return true;

        }

        if (!bridge.config().hasServerCredentials()) {

            sender.sendMessage(bridge.colorize(bridge.msg("config-missing")));

            return true;

        }



        bridge.getPlugin().getServer().getScheduler().runTaskAsynchronously(bridge.getPlugin(), () -> {

            try {

                CloudApiClient.LinkStatus status = resolveLinkStatus(player);

                LinkStartResult result = bridge.cloud().startLink(

                        player.getUniqueId().toString(), player.getName());

                bridge.getPlugin().getServer().getScheduler().runTask(bridge.getPlugin(), () -> {
                    sendLinkCode(player, result, status);
                    sendStatsLink(player);
                });

            } catch (Exception ex) {

                bridge.getPlugin().getServer().getScheduler().runTask(bridge.getPlugin(), () -> player.sendMessage(bridge.colorize(

                        bridge.msg("sync-fail").replace("{error}", ex.getMessage()))));

            }

        });

        return true;

    }

    private boolean handleStatus(CommandSender sender) {

        if (!(sender instanceof Player player)) {

            sender.sendMessage("Players only.");

            return true;

        }

        if (!sender.hasPermission("rootstat.status")) {

            sender.sendMessage(bridge.colorize(bridge.msg("no-permission")));

            return true;

        }

        if (!bridge.config().hasServerCredentials()) {

            sender.sendMessage(bridge.colorize(bridge.msg("config-missing")));

            return true;

        }



        bridge.getPlugin().getServer().getScheduler().runTaskAsynchronously(bridge.getPlugin(), () -> {

            try {

                CloudApiClient.LinkStatus status = resolveLinkStatus(player);

                bridge.getPlugin().getServer().getScheduler().runTask(bridge.getPlugin(), () -> {

                    if (status.linked()) {

                        sendLinkedMessage(player, status.displayLabel());

                    } else {

                        player.sendMessage(bridge.colorize(bridge.msg("link-not")));

                    }

                    sendStatsLink(player);

                });

            } catch (Exception ex) {

                bridge.getPlugin().getServer().getScheduler().runTask(bridge.getPlugin(), () -> player.sendMessage(bridge.colorize(

                        bridge.msg("sync-fail").replace("{error}", ex.getMessage()))));

            }

        });

        return true;

    }



    private CloudApiClient.LinkStatus resolveLinkStatus(Player player) throws Exception {

        if (bridge.players() != null) {

            Optional<LinkedPlayer> local = bridge.players().findByUuid(player.getUniqueId());

            if (local.isPresent() && local.get().verified()) {

                LinkedPlayer p = local.get();

                return new CloudApiClient.LinkStatus(
                        true,
                        false,
                        p.accountId(),
                        p.email(),
                        p.username(),
                        p.verifiedAt(),
                        null,
                        false,
                        false,
                        false);
            }

        }

        return bridge.cloud().linkStatus(player.getUniqueId().toString());

    }



    private void sendLinkCode(Player player, LinkStartResult result, CloudApiClient.LinkStatus status) {
        if (status.linked()) {
            player.sendMessage(Component.text()
                    .append(Component.text("App sign-in code: ", NamedTextColor.GREEN))
                    .append(Component.text(result.code(), NamedTextColor.YELLOW, TextDecoration.BOLD))
                    .append(Component.text(" — enter in the RootMC app ", NamedTextColor.GREEN))
                    .append(Component.text("(expires in 15 min)", NamedTextColor.GRAY))
                    .build());
            player.sendMessage(bridge.colorize(
                    bridge.msg("link-already").replace("{account}", status.displayLabel())));
            return;
        }

        player.sendMessage(Component.text()
                .append(Component.text("Verification code: ", NamedTextColor.GREEN))
                .append(Component.text(result.code(), NamedTextColor.YELLOW, TextDecoration.BOLD))
                .append(Component.text(" — open ", NamedTextColor.GREEN))
                .append(ChatLinks.url(result.verifyUrl()))
                .append(Component.text(" or DM the RootMC Discord bot: ", NamedTextColor.GREEN))
                .append(Component.text("link " + result.code(), NamedTextColor.YELLOW, TextDecoration.BOLD))
                .append(Component.text(" (expires in 15 min)", NamedTextColor.GREEN))
                .build());
    }

    private void sendLinkedMessage(Player player, String label) {

        bridge.getPlugin().getServer().getScheduler().runTask(bridge.getPlugin(), () -> player.sendMessage(bridge.colorize(

                bridge.msg("link-already").replace("{account}", label))));

    }



    private boolean handleShops(CommandSender sender) {
        if (!bridge.config().hasServerCredentials()) {
            sender.sendMessage(bridge.colorize(bridge.msg("config-missing")));
            return true;
        }
        String serverId = bridge.config().serverId();
        UUID playerUuid = sender instanceof Player player ? player.getUniqueId() : null;
        String url = RootStatUrls.shopsUrl(serverId, bridge.config().shopsUrlBase(), playerUuid);
        sender.sendMessage(ChatLinks.labeledUrl("Root Shops: ", url));
        return true;
    }

    private boolean handleMap(CommandSender sender) {
        if (!sender.hasPermission("rootstat.map")) {
            sender.sendMessage(bridge.colorize(bridge.msg("no-permission")));
            return true;
        }
        RootMcMapUrls.sendOpenMapMessage(
                sender,
                bridge.getPlugin(),
                null,
                bridge.msg("map-header"),
                bridge::colorize);
        return true;
    }

    private boolean handleValue(CommandSender sender, String[] args) {
        if (args.length < 2) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            MarketValueReport.sendCarriedValue(player, bridge);
            return true;
        }
        String query = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
        return valueCommand.onCommand(sender, null, "value", new String[] { query });
    }

    private void sendStatsLink(Player player) {
        String url = RootStatUrls.statsUrl(player.getUniqueId(), bridge.config().statsUrlBase());
        bridge.getPlugin().getServer().getScheduler().runTask(bridge.getPlugin(), () ->
                player.sendMessage(ChatLinks.labeledUrl("Public stats: ", url)));
    }



    private boolean handleSync(CommandSender sender) {

        if (!sender.hasPermission("rootstat.sync")) {

            sender.sendMessage(bridge.colorize(bridge.msg("no-permission")));

            return true;

        }

        if (bridge.players() == null) {

            sender.sendMessage(bridge.colorize(bridge.msg("mysql-disabled")));

            return true;

        }

        bridge.getPlugin().getServer().getScheduler().runTaskAsynchronously(bridge.getPlugin(), () -> {

            int count = bridge.syncTask().runSync(true);

            bridge.getPlugin().getServer().getScheduler().runTask(bridge.getPlugin(), () -> {

                if (count >= 0) {

                    sender.sendMessage(bridge.colorize(

                            bridge.msg("sync-done").replace("{count}", String.valueOf(count))));

                } else {

                    sender.sendMessage(bridge.colorize(bridge.msg("sync-fail").replace("{error}", "see console")));

                }

            });

        });

        return true;

    }



    private boolean handleReload(CommandSender sender) {

        if (!sender.hasPermission("rootstat.reload")) {

            sender.sendMessage(bridge.colorize(bridge.msg("no-permission")));

            return true;

        }

        bridge.reloadRootStatConfig();

        bridge.syncTask().start();

        sender.sendMessage(bridge.colorize("&aRootMC config reloaded."));

        return true;

    }

    /** Staff: why death fee did or did not apply (live config + treasury bridge). */
    private boolean handleDeathFee(CommandSender sender, String[] args) {
        if (!sender.hasPermission("rootstat.reload")) {
            sender.sendMessage(bridge.colorize(bridge.msg("no-permission")));
            return true;
        }
        if (!(bridge.getPlugin() instanceof RootMcPlugin rootMc)) {
            sender.sendMessage(bridge.colorize("&cRootMC plugin required."));
            return true;
        }
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(bridge.colorize("&cPlayer not online: &f" + args[1]));
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(bridge.colorize("&eUsage: /rootstat deathfee [player]"));
            return true;
        }

        ConfigurationSection death = rootMc.rootMcYaml().getConfigurationSection("treasury.death");
        boolean enabled = death != null && death.getBoolean("enabled", false);
        boolean pvpOnly = death != null && death.getBoolean("pvp-only", false);
        double percent = death == null ? 0.15 : death.getDouble("victim-balance-percent", 0.15);
        double minFee = death == null ? 1.0 : death.getDouble("min-fee-g", 1.0);
        var treasury = RootMcTreasuryResolver.resolve(rootMc);
        var grace = ShadedServiceBridge.resolveNewPlayerGrace(rootMc);
        boolean graceExempt = grace != null && grace.exemptFromDeathTax(target.getUniqueId());
        double balance = 0;
        var economy = RootMcEconomyResolver.resolve(rootMc);
        if (economy != null) {
            balance = economy.balance(target.getUniqueId());
        }
        double fee = GoldMoney.round(Math.min(balance, Math.max(minFee, balance * percent)));

        sender.sendMessage(bridge.colorize("&6Death fee diag &8— &f" + target.getName()));
        sender.sendMessage(bridge.colorize("&7rootmc.yml enabled: &f" + enabled + " &7pvp-only: &f" + pvpOnly));
        sender.sendMessage(bridge.colorize("&7Treasury bridge: &f" + (treasury != null ? "ok" : "&cmissing")));
        sender.sendMessage(bridge.colorize("&7Grace death-tax exempt: &f" + graceExempt));
        sender.sendMessage(bridge.colorize("&7Balance: &f" + GoldMoney.format(balance) + " G &7→ fee would be &f"
                + GoldMoney.format(fee) + " G"));
        sender.sendMessage(bridge.colorize("&7RootMC jar: &f" + rootMc.getDescription().getVersion()));
        if (!enabled) {
            sender.sendMessage(bridge.colorize("&cFix: upload &fplugins/RootMC/rootmc.yml &c(treasury.death.enabled: true) and restart."));
        } else if (treasury == null) {
            sender.sendMessage(bridge.colorize("&cFix: Root-Essentials MySQL/treasury not loaded — check console on boot."));
        }
        return true;
    }



    @Override

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {

        if (args.length == 1) {

            return List.of("link", "status", "map", "sync", "shops", "value", "reload", "deathfee").stream()

                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))

                    .toList();

        }

        return List.of();

    }

}


