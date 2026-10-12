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
        // /eterreward podium <1-5> (podium du classement général, avec EterNpc) ; le reste : administration du pass
        command(main, "eterreward").setExecutor((sender, command, label, args) -> {
            if (args.length > 0 && args[0].equalsIgnoreCase("podium")) {
                podium(main, sender, args);
                return true;
            }
            return admin.onCommand(sender, command, label, args);
        });
        command(main, "eterreward").setTabCompleter((sender, command, label, args) -> {
            if (args.length == 1) {
                java.util.List<String> all = new java.util.ArrayList<>(admin.onTabComplete(sender, command, label, args));
                if ("podium".startsWith(args[0].toLowerCase())) {
                    all.add("podium");
                }
                return all;
            }
            if (args[0].equalsIgnoreCase("podium")) {
                return args.length == 2 ? java.util.List.of("1", "2", "3", "4", "5") : List.of();
            }
            return admin.onTabComplete(sender, command, label, args);
        });

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

    private static void podium(Main main, org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            main.getMessages().send(sender, "command.players-only");
            return;
        }
        if (main.getPodium() == null) {
            main.getMessages().send(player, "ranking.podium.no-npc");
            return;
        }
        int place;
        try {
            place = Integer.parseInt(args.length > 1 ? args[1] : "");
        } catch (NumberFormatException e) {
            place = 0;
        }
        if (place < 1 || place > fr.eternom.eterReward.module.ranking.Podium.PLACES) {
            main.getMessages().send(player, "ranking.podium.usage");
            return;
        }
        ((fr.eternom.eterReward.module.ranking.Podium) main.getPodium()).place(player, place);
    }

    private static PluginCommand command(Main main, String name) {
        return Objects.requireNonNull(main.getCommand(name), "Commande absente du plugin.yml : " + name);
    }
}
