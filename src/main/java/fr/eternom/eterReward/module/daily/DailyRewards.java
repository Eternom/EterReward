package fr.eternom.eterReward.module.daily;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Récompenses des 7 jours (config.yml > daily.days) : chaque jour a une icône et plusieurs tirages possibles (loots),
 * dont un seul est donné, au hasard selon leur poids.
 */
public class DailyRewards {

    /** Objet donné : matière et quantité. */
    public record Reward(Material material, int amount) {
    }

    /**
     * Un tirage possible. label : texte affiché à la place du détail (utile pour une commande, ex : « Clé de caisse »).
     * Les commandes sont lancées par la console, {player} remplacé par le pseudo.
     */
    public record Loot(int weight, double money, List<Reward> items, List<String> commands, String label) {
    }

    public record Day(int number, Material icon, List<Loot> loots) {

        public int totalWeight() {
            return loots.stream().mapToInt(Loot::weight).sum();
        }

        public boolean givesMoney() {
            return loots.stream().anyMatch(loot -> loot.money() > 0);
        }

        /** Un tirage au hasard, selon les poids. */
        public Loot pick() {
            int roll = ThreadLocalRandom.current().nextInt(totalWeight());
            for (Loot loot : loots) {
                roll -= loot.weight();
                if (roll < 0) {
                    return loot;
                }
            }
            return loots.getLast();
        }
    }

    private final List<Day> days;

    private DailyRewards(List<Day> days) {
        this.days = days;
    }

    /** Jour 1 à 7. */
    public Day day(int number) {
        return days.get(number - 1);
    }

    /** Lit daily.days.1 à 7 ; un jour absent ou sans tirage valable rapporte 0 (avec un avertissement). */
    public static DailyRewards load(ConfigurationSection section, Logger logger) {
        List<Day> days = new ArrayList<>();
        for (int number = 1; number <= DailyState.CYCLE; number++) {
            ConfigurationSection entry = section == null ? null : section.getConfigurationSection(String.valueOf(number));
            Material icon = entry == null ? null : Material.matchMaterial(entry.getString("icon", "CHEST"));
            List<Loot> loots = new ArrayList<>();
            if (entry != null) {
                for (Map<?, ?> map : entry.getMapList("loots")) {
                    Loot loot = loot(map, number, logger);
                    if (loot != null) {
                        loots.add(loot);
                    }
                }
            }
            if (loots.isEmpty()) {
                logger.warning("daily.days." + number + " : aucun tirage valable, ce jour ne rapporte rien");
                loots.add(new Loot(1, 0, List.of(), List.of(), null));
            }
            days.add(new Day(number, icon == null || !icon.isItem() ? Material.CHEST : icon, List.copyOf(loots)));
        }
        return new DailyRewards(List.copyOf(days));
    }

    private static Loot loot(Map<?, ?> map, int day, Logger logger) {
        int weight = number(map.get("weight"), 1).intValue();
        double money = number(map.get("money"), 0).doubleValue();
        List<Reward> items = new ArrayList<>();
        if (map.get("items") instanceof List<?> list) {
            for (Object item : list) {
                // "DIAMOND 3" ou "DIAMOND"
                String[] parts = String.valueOf(item).trim().split("\\s+");
                Material material = Material.matchMaterial(parts[0].toUpperCase(Locale.ROOT));
                int amount = parts.length > 1 ? number(parts[1], 1).intValue() : 1;
                if (material == null || !material.isItem() || amount < 1) {
                    logger.warning("daily.days." + day + " : objet invalide ignoré : " + item);
                    continue;
                }
                items.add(new Reward(material, amount));
            }
        }
        List<String> commands = map.get("commands") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        String label = map.get("label") instanceof String text && !text.isBlank() ? text : null;
        if (weight < 1 || (money <= 0 && items.isEmpty() && commands.isEmpty())) {
            logger.warning("daily.days." + day + " : tirage vide ou poids < 1 ignoré");
            return null;
        }
        return new Loot(weight, Math.max(0, money), List.copyOf(items), commands, label);
    }

    private static Number number(Object value, Number fallback) {
        if (value instanceof Number number) {
            return number;
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
