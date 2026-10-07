package fr.eternom.eterReward.module.daily;

import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /daily : menu de la récompense quotidienne. */
public class DailyCommand implements CommandExecutor {

    private final DailyService daily;
    private final Messages messages;

    public DailyCommand(DailyService daily, Messages messages) {
        this.daily = daily;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player) {
            daily.open(player);
        } else {
            messages.send(sender, "command.players-only");
        }
        return true;
    }
}
