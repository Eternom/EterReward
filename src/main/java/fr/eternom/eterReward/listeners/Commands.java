package fr.eternom.eterReward.listeners;

import fr.eternom.eterReward.Main;
import fr.eternom.eterReward.module.AdminCommand;
import fr.eternom.eterReward.module.daily.DailyCommand;
import fr.eternom.eterReward.module.pass.PassCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public class Commands {

    public Commands(Main main) {
        PluginCommand daily = command(main, "daily");
        daily.setExecutor(new DailyCommand(main.getDaily(), main.getMessages()));
        daily.setTabCompleter((sender, command, label, args) -> List.of());

        register(main, "pass", new PassCommand(main.getPass(), main.getMessages(), false));
        register(main, "eterreward", new AdminCommand(new PassCommand(main.getPass(), main.getMessages(), true),
                main.getPodiumCommand(), main.getVotes(), main.getMessages()));

        register(main, "defis", menu(main, main.getChallenges()::open));
        register(main, "classement", menu(main, main.getRanking()::open));
    }

    /** Une commande sans argument qui ouvre un menu au joueur. */
    private static TabExecutor menu(Main main, Consumer<Player> open) {
        return new TabExecutor() {
            @Override
            public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
                if (sender instanceof Player player) {
                    open.accept(player);
                } else {
                    main.getMessages().send(sender, "command.players-only");
                }
                return true;
            }

            @Override
            public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
                return List.of();
            }
        };
    }

    private static void register(Main main, String name, TabExecutor executor) {
        command(main, name).setExecutor(executor);
        command(main, name).setTabCompleter(executor);
    }

    private static PluginCommand command(Main main, String name) {
        return Objects.requireNonNull(main.getCommand(name), "Commande absente du plugin.yml : " + name);
    }
}
