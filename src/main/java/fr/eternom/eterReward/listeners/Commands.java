package fr.eternom.eterReward.listeners;

import fr.eternom.eterReward.Main;
import fr.eternom.eterReward.module.daily.DailyCommand;
import fr.eternom.eterReward.module.pass.PassCommand;
import org.bukkit.command.PluginCommand;

import java.util.List;
import java.util.Objects;

public class Commands {

    public Commands(Main main) {
        PluginCommand daily = command(main, "daily");
        daily.setExecutor(new DailyCommand(main.getDaily(), main.getMessages()));
        daily.setTabCompleter((sender, command, label, args) -> List.of());

        PassCommand pass = new PassCommand(main.getPass(), main.getMessages(), false);
        command(main, "pass").setExecutor(pass);
        command(main, "pass").setTabCompleter(pass);
        PassCommand admin = new PassCommand(main.getPass(), main.getMessages(), true);
        command(main, "eterreward").setExecutor(admin);
        command(main, "eterreward").setTabCompleter(admin);

        command(main, "defis").setExecutor((sender, command, label, args) -> {
            if (sender instanceof org.bukkit.entity.Player player) {
                main.getChallenges().open(player);
            } else {
                main.getMessages().send(sender, "command.players-only");
            }
            return true;
        });
        command(main, "defis").setTabCompleter((sender, command, label, args) -> List.of());

        command(main, "classement").setExecutor((sender, command, label, args) -> {
            if (sender instanceof org.bukkit.entity.Player player) {
                main.getRanking().open(player);
            } else {
                main.getMessages().send(sender, "command.players-only");
            }
            return true;
        });
        command(main, "classement").setTabCompleter((sender, command, label, args) -> List.of());
    }

    private static PluginCommand command(Main main, String name) {
        return Objects.requireNonNull(main.getCommand(name), "Commande absente du plugin.yml : " + name);
    }
}
