package fr.eternom.eterReward.module.pass;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Un pass de saison (un fichier de plugins/EterReward/passes/) : son nom, son icône, son bonus du mois et ses paliers,
 * chacun avec une récompense gratuite et une récompense premium.
 */
public record PassDefinition(String id, String name, Material icon, List<String> description, Bonus bonus, List<Tier> tiers) {

    /**
     * Le bonus du mois. questJob : les quêtes de ce métier rapportent questMultiplier fois plus d'XP (vide : toutes les
     * quêtes) ; dailyMultiplier : l'XP de /daily ; playtimeCap : plafond d'XP de temps de jeu par jour (0 : celui de la
     * config) ; challengeMultiplier : l'XP des défis ; voteMultiplier : l'XP de pass gagnée en votant.
     */
    public record Bonus(String questJob, double questMultiplier, double dailyMultiplier, int playtimeCap,
                        double challengeMultiplier, double voteMultiplier) {

        static final Bonus NONE = new Bonus("", 1, 1, 0, 1, 1);
    }

    /** Un objet donné : matière et quantité. */
    public record ItemReward(Material material, int amount) {
    }

    /** Une permission donnée pour une durée (LuckPerms), indépendante de la fin du pass. */
    public record PermissionReward(String permission, Duration duration) {
    }

    /**
     * Ce que donne un palier d'une ligne (gratuite ou premium). keys : clés de monde ressource ; resourceMinutes : temps
     * de monde ressource ; commands : lancées par la console, {player} = le pseudo ; label : texte affiché à la place du
     * détail (MiniMessage).
     */
    public record Reward(double money, int keys, int resourceMinutes, List<ItemReward> items,
                         List<PermissionReward> permissions, List<String> commands, String label) {

        boolean isEmpty() {
            return money <= 0 && keys <= 0 && resourceMinutes <= 0 && items.isEmpty() && permissions.isEmpty() && commands.isEmpty();
        }

        /** Icône du palier dans le menu : ce que la récompense donne de plus parlant. */
        Material icon() {
            if (!items.isEmpty()) {
                return items.getFirst().material();
            }
            if (keys > 0 || resourceMinutes > 0) {
                return Material.TRIPWIRE_HOOK;
            }
            if (!permissions.isEmpty()) {
                return Material.NAME_TAG;
            }
            if (money > 0) {
                return Material.GOLD_NUGGET;
            }
            return Material.CHEST;
        }
    }

    /** free ou premium peut être null (rien sur cette ligne pour ce palier). */
    public record Tier(int number, Reward free, Reward premium) {
    }

    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*([dhm])", Pattern.CASE_INSENSITIVE);

    /** Lit un fichier de pass ; null s'il est inutilisable (avec un avertissement). */
    static PassDefinition load(File file, int tierCount, Logger logger) {
        String id = file.getName().replaceFirst("\\.yml$", "").toLowerCase(Locale.ROOT);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String where = "passes/" + file.getName();
        if (!yaml.getBoolean("enabled", true)) {
            logger.info(where + " : désactivé (enabled: false)");
            return null;
        }
        Material icon = Material.matchMaterial(yaml.getString("icon", "NETHER_STAR"));
        ConfigurationSection bonusSection = yaml.getConfigurationSection("bonus");
        Bonus bonus = bonusSection == null ? Bonus.NONE : new Bonus(
                bonusSection.getString("quest-job", "").toLowerCase(Locale.ROOT),
                Math.max(0, bonusSection.getDouble("quest-multiplier", 1)),
                Math.max(0, bonusSection.getDouble("daily-multiplier", 1)),
                Math.max(0, bonusSection.getInt("playtime-cap", 0)),
                Math.max(0, bonusSection.getDouble("challenge-multiplier", 1)),
                Math.max(0, bonusSection.getDouble("vote-multiplier", 1)));

        List<Tier> tiers = new ArrayList<>();
        ConfigurationSection tierSection = yaml.getConfigurationSection("tiers");
        for (int number = 1; number <= tierCount; number++) {
            ConfigurationSection entry = tierSection == null ? null : tierSection.getConfigurationSection(String.valueOf(number));
            Reward free = entry == null ? null : reward(entry.getConfigurationSection("free"), where + " palier " + number, logger);
            Reward premium = entry == null ? null : reward(entry.getConfigurationSection("premium"), where + " palier " + number, logger);
            tiers.add(new Tier(number, free, premium));
        }
        if (tiers.stream().allMatch(tier -> tier.free() == null && tier.premium() == null)) {
            logger.warning(where + " : aucun palier valable, pass ignoré");
            return null;
        }
        return new PassDefinition(id, yaml.getString("name", id), icon == null || !icon.isItem() ? Material.NETHER_STAR : icon,
                yaml.getStringList("description"), bonus, List.copyOf(tiers));
    }

    private static Reward reward(ConfigurationSection section, String where, Logger logger) {
        if (section == null) {
            return null;
        }
        List<ItemReward> items = new ArrayList<>();
        for (String item : section.getStringList("items")) {
            // "DIAMOND 3" ou "DIAMOND"
            String[] parts = item.trim().split("\\s+");
            Material material = Material.matchMaterial(parts[0].toUpperCase(Locale.ROOT));
            int amount = parts.length > 1 ? parse(parts[1]) : 1;
            if (material == null || !material.isItem() || amount < 1) {
                logger.warning(where + " : objet invalide ignoré : " + item);
                continue;
            }
            items.add(new ItemReward(material, amount));
        }
        List<PermissionReward> permissions = new ArrayList<>();
        for (String permission : section.getStringList("permissions")) {
            // "eteressential.craft 7d" : la permission et sa durée (d jours, h heures, m minutes)
            String[] parts = permission.trim().split("\\s+", 2);
            Duration duration = parts.length > 1 ? duration(parts[1]) : null;
            if (parts[0].isBlank() || duration == null) {
                logger.warning(where + " : permission invalide ignorée (il faut une durée, ex : 7d) : " + permission);
                continue;
            }
            permissions.add(new PermissionReward(parts[0], duration));
        }
        String label = section.getString("label");
        Reward reward = new Reward(Math.max(0, section.getDouble("money")), Math.max(0, section.getInt("keys")),
                Math.max(0, section.getInt("resource-minutes")), List.copyOf(items), List.copyOf(permissions),
                section.getStringList("commands"), label == null || label.isBlank() ? null : label);
        return reward.isEmpty() ? null : reward;
    }

    /** "7d", "12h", "30m", "1d 12h" ; null si illisible. */
    static Duration duration(String text) {
        Matcher matcher = DURATION.matcher(text);
        Duration total = Duration.ZERO;
        int end = 0;
        while (matcher.find()) {
            if (!text.substring(end, matcher.start()).isBlank()) {
                return null;
            }
            long value = Long.parseLong(matcher.group(1));
            total = total.plus(switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "d" -> Duration.ofDays(value);
                case "h" -> Duration.ofHours(value);
                default -> Duration.ofMinutes(value);
            });
            end = matcher.end();
        }
        return end == 0 || !text.substring(end).isBlank() || total.isZero() ? null : total;
    }

    private static int parse(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** XP de quête selon le bonus du mois. */
    double questMultiplier(String job) {
        return bonus.questJob().isEmpty() || bonus.questJob().equals(job) ? bonus.questMultiplier() : 1;
    }
}
