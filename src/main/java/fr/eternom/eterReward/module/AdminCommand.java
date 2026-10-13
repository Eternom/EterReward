package fr.eternom.eterReward.module;

import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /eterreward (staff) : « pass ... » pour l'administration du pass, « podium <1-5> » pour le podium du classement
 * général (null sans EterNpc sur ce serveur).
 */
public class AdminCommand implements TabExecutor {

    private final TabExecutor pass;
    private final TabExecutor podium;
    private final Messages messages;

    public AdminCommand(TabExecutor pass, TabExecutor podium, Messages messages) {
        this.pass = pass;
        this.podium = podium;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("podium")) {
            return pass.onCommand(sender, command, label, args);
        }
        if (podium == null) {
            messages.send(sender, "ranking.podium.no-npc");
            return true;
        }
        return podium.onCommand(sender, command, label, args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            List<String> actions = new ArrayList<>(pass.onTabComplete(sender, command, label, args));
            actions.add("podium");
            return actions.stream().filter(action -> action.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args[0].equalsIgnoreCase("podium")) {
            return podium == null ? List.of() : podium.onTabComplete(sender, command, label, args);
        }
        return pass.onTabComplete(sender, command, label, args);
    }
}
