package fr.eternom.eterReward.module.ranking;

import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.IntStream;

/** /eterreward podium <1-5> : pose une place du podium là où est le staff. Créée seulement si EterNpc tourne. */
public class PodiumCommand implements TabExecutor {

    private final Podium podium;
    private final Messages messages;

    public PodiumCommand(Podium podium, Messages messages) {
        this.podium = podium;
        this.messages = messages;
    }

    /** args : ["podium", "<place>"]. */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.players-only");
            return true;
        }
        int place = args.length > 1 ? parse(args[1]) : 0;
        if (place < 1 || place > Podium.PLACES) {
            messages.send(player, "ranking.podium.usage");
        } else {
            podium.place(player, place);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return args.length == 2 ? IntStream.rangeClosed(1, Podium.PLACES).mapToObj(String::valueOf).toList() : List.of();
    }

    private static int parse(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
