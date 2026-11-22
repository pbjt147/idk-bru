package com.example.gheadcore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TweakCommand implements CommandExecutor, TabCompleter {

    private static final String USAGE =
            "Usage: /gheadcore [config|reload|ultimate <name>|resetcooldowns [player]|altar|givesword [player]]";

    private final TweakConfig config;
    private final TweakMenu menu;
    private final UltimateManager ultimates;
    private final AltarManager altars;
    private final AltarSword sword;

    public TweakCommand(TweakConfig config, TweakMenu menu, UltimateManager ultimates,
                        AltarManager altars, AltarSword sword) {
        this.config = config;
        this.menu = menu;
        this.ultimates = ultimates;
        this.altars = altars;
        this.sword = sword;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "config" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "config" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can open the GUI.");
                } else if (!player.hasPermission("gheadcore.config")) {
                    player.sendMessage("You don't have permission.");
                } else {
                    menu.openMain(player);
                }
            }
            case "reload" -> {
                if (!sender.hasPermission("gheadcore.config")) {
                    sender.sendMessage("You don't have permission.");
                } else {
                    config.reload();
                    sender.sendMessage("GheadCORE config reloaded.");
                }
            }
            case "ultimate" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can activate ultimates.");
                } else if (!player.hasPermission("gheadcore.ultimate")) {
                    player.sendMessage("You don't have permission.");
                } else if (args.length < 2) {
                    player.sendMessage("Usage: /gheadcore ultimate <trident|mace|spear|crossbow>");
                } else {
                    try {
                        ultimates.activate(player, Ultimate.valueOf(args[1].toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException e) {
                        player.sendMessage("Unknown ultimate. Use trident, mace, spear or crossbow.");
                    }
                }
            }
            case "resetcooldowns" -> {
                if (!sender.hasPermission("gheadcore.config")) {
                    sender.sendMessage("You don't have permission.");
                } else {
                    Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1])
                            : (sender instanceof Player p ? p : null);
                    if (target == null) {
                        sender.sendMessage("Player not found.");
                    } else {
                        ultimates.resetCooldowns(target);
                        sender.sendMessage("Reset ultimates and cooldowns for " + target.getName() + ".");
                    }
                }
            }
            case "altar" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can place an altar.");
                } else if (!player.hasPermission("gheadcore.config")) {
                    player.sendMessage("You don't have permission.");
                } else {
                    // Three blocks in front of you, at your feet level.
                    Vector forward = player.getLocation().getDirection().setY(0);
                    if (forward.lengthSquared() < 1.0E-4) forward = new Vector(1, 0, 0);
                    Location base = player.getLocation().add(forward.normalize().multiply(3));
                    altars.spawnAltar(base);
                    player.sendMessage("Altar placed.");
                }
            }
            case "givesword" -> {
                if (!sender.hasPermission("gheadcore.config")) {
                    sender.sendMessage("You don't have permission.");
                } else {
                    Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1])
                            : (sender instanceof Player p ? p : null);
                    if (target == null) {
                        sender.sendMessage("Player not found.");
                    } else {
                        target.getInventory().addItem(sword.create());
                        sender.sendMessage("Gave The Altar's Sword to " + target.getName() + ".");
                    }
                }
            }
            default -> sender.sendMessage(USAGE);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("config", "reload", "ultimate", "resetcooldowns", "altar", "givesword"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("ultimate")) {
            for (Ultimate u : Ultimate.values()) out.add(u.name().toLowerCase(Locale.ROOT));
        } else if (args.length == 2
                && (args[0].equalsIgnoreCase("resetcooldowns") || args[0].equalsIgnoreCase("givesword"))) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
