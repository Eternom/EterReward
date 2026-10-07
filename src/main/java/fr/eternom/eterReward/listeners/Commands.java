package fr.eternom.eterReward.listeners;

import fr.eternom.eterReward.Main;
import fr.eternom.eterReward.module.daily.DailyCommand;
import org.bukkit.command.PluginCommand;

import java.util.List;
import java.util.Objects;

public class Commands {

    public Commands(Main main) {
        PluginCommand daily = Objects.requireNonNull(main.getCommand("daily"), "Commande absente du plugin.yml : daily");
        daily.setExecutor(new DailyCommand(main.getDaily(), main.getMessages()));
        daily.setTabCompleter((sender, command, label, args) -> List.of());
    }
}
