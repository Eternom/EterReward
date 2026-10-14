package fr.eternom.eterReward.module;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterReward.module.vote.VoteService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /eterreward (staff, console) : « pass ... » pour l'administration du pass, « podium <1-5> » pour le podium du
 * classement général (null sans EterNpc sur ce serveur), « vote <joueur> » pour un vote validé (lancé par le site).
 */
public class AdminCommand implements TabExecutor {

    private final TabExecutor pass;
    private final TabExecutor podium;
    private final VoteService votes;
    private final Messages messages;

    public AdminCommand(TabExecutor pass, TabExecutor podium, VoteService votes, Messages messages) {
        this.pass = pass;
        this.podium = podium;
        this.votes = votes;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "vote" -> {
                if (args.length == 2) {
                    votes.vote(sender, args[1]);
                } else {
                    messages.send(sender, "vote.usage");
                }
            }
            case "podium" -> {
                if (podium == null) {
                    messages.send(sender, "ranking.podium.no-npc");
                } else {
                    podium.onCommand(sender, command, label, args);
                }
            }
            default -> pass.onCommand(sender, command, label, args);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            List<String> actions = new ArrayList<>(pass.onTabComplete(sender, command, label, args));
            actions.add("podium");
            actions.add("vote");
            return actions.stream().filter(action -> action.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "vote" -> args.length == 2 ? EterLib.get().getOnlineNames().complete(args[1]) : List.of();
            case "podium" -> podium == null ? List.of() : podium.onTabComplete(sender, command, label, args);
            default -> pass.onTabComplete(sender, command, label, args);
        };
    }
}
