package fr.eternom.eterReward.module.vote;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Récompenses des votes (config.yml > votes) : un seul tirage par vote parmi loots, au hasard selon leur poids, et un
 * bonus aux jours de série (streak.<jours>). Pas d'objets : le joueur peut voter hors ligne ou être sur un autre serveur.
 */
public class VoteRewards {

    /** Argent, XP du pass, clés de ressource, commandes console ({player}) ; label : texte affiché à la place du détail. */
    public record Loot(int weight, double money, int passXp, int keys, List<String> commands, String label) {

        boolean isEmpty() {
            return money <= 0 && passXp <= 0 && keys <= 0 && commands.isEmpty();
        }
    }

    private final List<Loot> loots;
    private final Map<Integer, Loot> streak;

    private VoteRewards(List<Loot> loots, Map<Integer, Loot> streak) {
        this.loots = loots;
        this.streak = streak;
    }

    /** Le tirage d'un vote, null si aucun n'est configuré. */
    Loot pick() {
        int total = loots.stream().mapToInt(Loot::weight).sum();
        if (total <= 0) {
            return null;
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (Loot loot : loots) {
            roll -= loot.weight();
            if (roll < 0) {
                return loot;
            }
        }
        return loots.getLast();
    }

    /** Bonus pour une série de days jours, null s'il n'y en a pas pour ce nombre. */
    Loot streakBonus(int days) {
        return streak.get(days);
    }

    public static VoteRewards load(ConfigurationSection section, Logger logger) {
        List<Loot> loots = new ArrayList<>();
        Map<Integer, Loot> streak = new HashMap<>();
        if (section != null) {
            MemoryConfiguration holder = new MemoryConfiguration();
            for (Map<?, ?> map : section.getMapList("loots")) {
                Loot loot = loot(holder.createSection("loot", map));
                if (loot.weight() < 1 || loot.isEmpty()) {
                    logger.warning("votes.loots : tirage vide ou poids < 1 ignoré : " + map);
                } else {
                    loots.add(loot);
                }
            }
            ConfigurationSection days = section.getConfigurationSection("streak");
            for (String key : days == null ? List.<String>of() : days.getKeys(false)) {
                Loot loot = days.isConfigurationSection(key) ? loot(days.getConfigurationSection(key)) : null;
                int number = parseDays(key);
                if (number < 2 || loot == null || loot.isEmpty()) {
                    logger.warning("votes.streak." + key + " : bonus de série invalide ignoré (jours >= 2, au moins une récompense)");
                } else {
                    streak.put(number, loot);
                }
            }
        }
        if (loots.isEmpty()) {
            logger.warning("votes.loots : aucun tirage valable, un vote ne rapporte rien (il compte quand même au classement)");
        }
        return new VoteRewards(List.copyOf(loots), Map.copyOf(streak));
    }

    private static Loot loot(ConfigurationSection entry) {
        String label = entry.getString("label");
        return new Loot(entry.getInt("weight", 1), Math.max(0, entry.getDouble("money")), Math.max(0, entry.getInt("pass-xp")),
                Math.max(0, entry.getInt("keys")), List.copyOf(entry.getStringList("commands")),
                label == null || label.isBlank() ? null : label);
    }

    private static int parseDays(String key) {
        try {
            return Integer.parseInt(key.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
