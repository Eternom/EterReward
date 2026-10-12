package fr.eternom.eterReward.module.pass;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * /pass : le menu du pass. /eterreward pass ... (staff, aussi depuis la console, ex : une boutique) :
 * premium <joueur> (premium du pass en cours jusqu'à la fin du mois), set <pass> (forcer le pass du mois),
 * xp <joueur> <montant>.
 */
public class PassCommand implements TabExecutor {

    public static final String ADMIN_PERMISSION = "eterreward.admin";

    private final PassService pass;
    private final Messages messages;
    private final boolean admin;

    public PassCommand(PassService pass, Messages messages, boolean admin) {
        this.pass = pass;
        this.messages = messages;
        this.admin = admin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!admin) {
            if (sender instanceof Player player) {
                pass.open(player);
            } else {
                messages.send(sender, "command.players-only");
            }
            return true;
        }
        if (args.length < 2 || !args[0].equalsIgnoreCase("pass")) {
            messages.send(sender, "pass.admin-usage");
            return true;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "premium" -> {
                if (args.length < 3) {
                    messages.send(sender, "pass.admin-usage");
                    return true;
                }
                pass.givePremium(sender, args[2]);
            }
            case "set" -> {
                if (args.length < 3) {
                    messages.send(sender, "pass.admin-usage");
                    return true;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                Tasks.async(pass.plugin(), () -> {
                    boolean done = pass.force(id);
                    Bukkit.getScheduler().runTask(pass.plugin(), () -> messages.send(sender,
                            done ? "pass.set" : "pass.unknown", "pass", id, "list", String.join(", ", pass.passIds())));
                }, "Pass du mois non changé");
            }
            case "xp" -> {
                int amount = args.length < 4 ? 0 : parse(args[3]);
                if (amount <= 0) {
                    messages.send(sender, "pass.admin-usage");
                    return true;
                }
                String name = args[2];
                Tasks.async(pass.plugin(), () -> {
                    var target = EterLib.get().getPlayers().find(name);
                    target.ifPresent(found -> pass.addXp(found.uuid(), amount));
                    Bukkit.getScheduler().runTask(pass.plugin(), () -> {
                        if (target.isPresent()) {
                            messages.send(sender, "pass.xp-given", "player", target.get().name(), "xp", String.valueOf(amount));
                        } else {
                            messages.send(sender, "player.unknown", "player", name);
                        }
                    });
                }, "XP du pass non donnée à " + name);
            }
            default -> messages.send(sender, "pass.admin-usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!admin) {
            return List.of();
        }
        return switch (args.length) {
            case 1 -> List.of("pass");
            case 2 -> List.of("premium", "set", "xp");
            case 3 -> args[1].equalsIgnoreCase("set") ? List.copyOf(pass.passIds())
                    : EterLib.get().getOnlineNames().complete(args[2]);
            default -> List.of();
        };
    }

    private static int parse(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
